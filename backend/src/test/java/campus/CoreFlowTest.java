package campus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:core;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;LOCK_TIMEOUT=1000"})
class CoreFlowTest {
    @LocalServerPort int port;
    @Autowired Database db;
    @Autowired ObjectMapper mapper;
    final HttpClient client=HttpClient.newHttpClient();
    final Map<Integer,String> tokens=new HashMap<>();
    record Reply(int status,JsonNode body) {}
    Reply call(int user,String method,String path,Object body,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(tokens.containsKey(user)) request.header("Authorization","Bearer "+tokens.get(user));
        if(key!=null)request.header("Idempotency-Key",key);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),response.body().isEmpty()?mapper.nullNode():mapper.readTree(response.body()));
    }
    Reply write(int user,String method,String path,Object body) throws Exception { return call(user,method,path,body,UUID.randomUUID().toString()); }
    @BeforeEach void setup() throws Exception {
        for(String table:List.of("audit_log","idempotency","api_session","review","submission","registration","activity"))db.update("DELETE FROM "+table);
        db.update("UPDATE role_grant SET status='ACTIVE' WHERE id<=4");
        for(int i=1;i<=6;i++) {
            var reply=call(0,"POST","/demo/sessions",Map.of("accountId",i),null);
            assertEquals(200,reply.status,reply.body.toString());tokens.put(i,reply.body.get("accessToken").asText());
        }
    }
    String draft(int capacity) throws Exception {
        var now=OffsetDateTime.now();
        var body=Map.of("title","旧书交换日","category","LECTURE","location","图书馆","description","读书分享活动","capacity",capacity,"registrationClosesAt",now.plusMinutes(5).toString(),"startsAt",now.plusMinutes(10).toString(),"endsAt",now.plusHours(1).toString());
        var reply=write(3,"POST","/management/clubs/1/activities",body);
        assertEquals(201,reply.status,reply.body.toString());return reply.body.get("id").asText();
    }
    String submit(String id) throws Exception {
        var sub=write(3,"POST","/management/activities/"+id+"/submissions",null);
        assertEquals(201,sub.status,sub.body.toString());return sub.body.get("id").asText();
    }
    void approve(String id,String sub) throws Exception {
        assertEquals(200,write(4,"PUT","/management/activities/"+id+"/submissions/"+sub+"/review",Map.of("decision","APPROVED")).status);
    }
    @Test void reviewSignupCancellationAndCheckin() throws Exception {
        String id=draft(2), sub=submit(id);
        assertEquals(404,call(1,"GET","/activities/"+id,null,null).status);
        assertEquals(403,call(6,"GET","/management/activities/"+id,null,null).status);
        assertEquals(400,write(4,"PUT","/management/activities/"+id+"/submissions/"+sub+"/review",Map.of("decision","RETURNED")).status);
        approve(id,sub);
        var first=call(1,"POST","/activities/"+id+"/registrations",null,"same-intent");
        assertEquals(201,first.status,first.body.toString());
        assertEquals(first.body,call(1,"POST","/activities/"+id+"/registrations",null,"same-intent").body);
        String reg=first.body.get("id").asText();
        assertEquals(404,call(2,"GET","/me/registrations/"+reg,null,null).status);
        assertEquals(200,write(1,"PUT","/me/registrations/"+reg+"/cancellation",Map.of("confirmed",true)).status);
        assertEquals(200,write(1,"PUT","/me/registrations/"+reg+"/cancellation",Map.of("confirmed",true)).status);
        var second=write(1,"POST","/activities/"+id+"/registrations",null);
        assertEquals(201,second.status,second.body.toString());assertNotEquals(reg,second.body.get("id").asText());
        String checkPath="/management/activities/"+id+"/registrations/"+second.body.get("id").asText()+"/check-in";
        var checked=write(3,"PUT",checkPath,null);assertEquals(200,checked.status,checked.body.toString());
        assertEquals(checked.body.get("checkedInAt"),write(3,"PUT",checkPath,null).body.get("checkedInAt"));
        assertEquals(1,db.one("SELECT registered_count FROM activity WHERE id=?",id).get("registered_count"));
        assertEquals(200,write(3,"PUT","/management/activities/"+id+"/cancellation",Map.of("confirmed",true)).status);
        assertEquals("ACTIVITY_CANCELLED",call(1,"GET","/me/registrations/"+second.body.get("id").asText(),null,null).body.get("displayStatus").asText());
        assertEquals(409,write(2,"POST","/activities/"+id+"/registrations",null).status);
    }
    @Test void lastSeatIsNeverOversold() throws Exception {
        String id=draft(1);approve(id,submit(id));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            List<Future<Reply>> futures=new ArrayList<>();
            for(int student:List.of(1,2)) futures.add(executor.submit(()->{start.await();return write(student,"POST","/activities/"+id+"/registrations",null);}));
            start.countDown();
            var statuses=new ArrayList<Integer>();
            for(var future:futures) statuses.add(future.get(10,TimeUnit.SECONDS).status);
            Collections.sort(statuses);assertEquals(List.of(201,409),statuses);
        }
        assertEquals(1,db.one("SELECT registered_count FROM activity WHERE id=?",id).get("registered_count"));
        assertEquals(1L,((Number)db.one("SELECT COUNT(*) AS total FROM registration WHERE activity_id=? AND active_marker=1",id).get("total")).longValue());
    }
    @Test void roleRevocationAppliesToExistingSession() throws Exception {
        String id=draft(1);
        var grant=db.one("SELECT id FROM role_grant WHERE account_id=3 AND role='CLUB_MANAGER' AND status='ACTIVE'").get("id");
        assertEquals(204,write(5,"DELETE","/management/accounts/3/role-grants/"+grant,null).status);
        assertEquals(403,call(3,"GET","/management/activities/"+id,null,null).status);
        assertEquals(403,write(3,"POST","/management/activities/"+id+"/submissions",null).status);
    }
    @Test void validationAndIdempotencyConflict() throws Exception {
        String id=draft(1),sub=submit(id);
        assertEquals(403,write(1,"PUT","/management/activities/"+id+"/submissions/"+sub+"/review",Map.of("decision","APPROVED")).status);
        String path="/management/activities/"+id+"/submissions/"+sub+"/review";
        assertEquals(200,call(4,"PUT",path,Map.of("decision","APPROVED"),"review-intent").status);
        assertEquals(409,call(4,"PUT",path,Map.of("decision","RETURNED","comment","重试内容不同"),"review-intent").status);
        assertEquals(400,call(1,"GET","/activities?page=0",null,null).status);
    }
}
