package campus;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static campus.Database.*;
import static campus.ApiError.require;

@RestController
@RequestMapping("/api/v1")
public class Api {
    final Database db;
    final Identity auth;
    final Activities activities;
    final Writes writes;
    public Api(Database db,Identity auth,Activities activities,Writes writes) {
        this.db=db; this.auth=auth; this.activities=activities; this.writes=writes;
    }
    long user(HttpServletRequest request) { return auth.actor(request); }
    ResponseEntity<?> write(HttpServletRequest request,Object body,Runnable authorize,java.util.function.Supplier<Writes.Result> action) {
        var result=writes.execute(user(request),request.getMethod()+" "+request.getRequestURI(),request.getHeader("Idempotency-Key"),body,authorize,action);
        var builder=ResponseEntity.status(result.status());
        if(result.location()!=null) builder.header("Location",result.location());
        if(result.body() instanceof Map<?,?> map && map.get("version")!=null) builder.eTag(map.get("version").toString());
        return builder.body(result.body());
    }
    Runnable managed(long actor,long id) { return ()->activities.manager(actor,activities.activity(id,false)); }
    @GetMapping("/demo/accounts") Object demoAccounts() {
        require(auth.demo,404,"NOT_FOUND","演示登录已关闭");
        return db.rows("SELECT id AS account_id,display_name FROM account ORDER BY id").stream().map(Database::external).toList();
    }
    @PostMapping("/demo/sessions") Object login(@RequestBody Map<String,Object> body) { return auth.login(Activities.id(body.get("accountId"))); }
    @GetMapping("/me") Object me(HttpServletRequest req) {
        long actor=user(req);
        var result=external(db.one("SELECT id AS account_id,display_name FROM account WHERE id=?",actor));
        var roles=new ArrayList<>(db.rows("SELECT DISTINCT role FROM role_grant WHERE account_id=? AND status='ACTIVE'",actor).stream().map(r->r.get("role").toString()).toList());
        roles.add("STUDENT"); result.put("roles",roles); return result;
    }
    @GetMapping("/activity-categories") Object categories(HttpServletRequest req) { user(req); return db.rows("SELECT * FROM category ORDER BY code"); }
    @GetMapping("/activities") Object list(HttpServletRequest req,@RequestParam(defaultValue="") String q,@RequestParam(required=false) String category,@RequestParam(defaultValue="START_ASC") String sort,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        user(req); require(q.length()<=100&&Set.of("START_ASC","PUBLISHED_DESC").contains(sort),400,"VALIDATION_FAILED","查询条件不合法");
        String order=sort.equals("START_ASC")?"starts_at ASC,id ASC":"published_at DESC,id ASC";
        return db.page("SELECT * FROM activity WHERE status='PUBLISHED' AND title LIKE ? AND (? IS NULL OR category=?) ORDER BY "+order,Arrays.asList("%"+q.strip()+"%",category,category),page,pageSize,activities::summary);
    }
    @GetMapping("/activities/{id}") Object detail(HttpServletRequest req,@PathVariable long id) { return activities.detail(user(req),id,false); }
    @PostMapping("/activities/{id}/registrations") Object signup(HttpServletRequest req,@PathVariable long id) {
        long actor=user(req);
        return write(req,Map.of(),()->{},()->{
            var created=activities.signup(actor,id);
            return new Writes.Result(created.created()?201:200,activities.registration(actor,created.id()),"/api/v1/me/registrations/"+created.id());
        });
    }
    @GetMapping("/me/registrations") Object registrations(HttpServletRequest req,@RequestParam(required=false) String displayStatus,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        long actor=user(req);
        return db.page("SELECT r.id FROM registration r JOIN activity a ON a.id=r.activity_id WHERE r.student_id=? AND (? IS NULL OR CASE WHEN a.status='CANCELLED' THEN 'ACTIVITY_CANCELLED' ELSE r.status END=?) ORDER BY r.registered_at DESC,r.id ASC",Arrays.asList(actor,displayStatus,displayStatus),page,pageSize,r->activities.registration(actor,number(r,"id")));
    }
    @GetMapping("/me/registrations/{id}") Object registration(HttpServletRequest req,@PathVariable long id) { return activities.registration(user(req),id); }
    @PutMapping("/me/registrations/{id}/cancellation") Object cancelRegistration(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body) {
        long actor=user(req);
        return write(req,body,()->db.one("SELECT id FROM registration WHERE id=? AND student_id=?",id,actor),()->Writes.Result.ok(activities.cancelRegistration(actor,id,body)));
    }
    @GetMapping("/me/registrations/{id}/check-in") Object checkinView(HttpServletRequest req,@PathVariable long id) {
        var registration=activities.registration(user(req),id);
        @SuppressWarnings("unchecked") var a=(Map<String,Object>)registration.get("activity");
        var result=new LinkedHashMap<String,Object>();
        result.put("registrationId",Long.toString(id)); result.put("activityId",a.get("id")); result.put("location",a.get("location"));
        result.put("opensAt",java.time.OffsetDateTime.parse(a.get("startsAt").toString()).minusMinutes(30).toString());
        result.put("closesAt",a.get("endsAt"));
        String state=registration.get("displayStatus").toString();
        result.put("status",switch(state) {case "CHECKED_IN"->"CHECKED_IN"; case "REGISTERED"->"PENDING"; default->"UNAVAILABLE";});
        result.put("checkedInAt",registration.get("checkedInAt")); return result;
    }
    @GetMapping("/management/clubs/{club}/activities") Object managedActivities(HttpServletRequest req,@PathVariable long club,@RequestParam(required=false) String status,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        long actor=user(req); auth.requireRole(actor,"CLUB_MANAGER",club);
        return db.page("SELECT id FROM activity WHERE club_id=? AND (? IS NULL OR status=?) AND title LIKE ? ORDER BY updated_at DESC,id ASC",Arrays.asList(club,status,status,"%"+q.strip()+"%"),page,pageSize,r->activities.detail(actor,number(r,"id"),true));
    }
    @PostMapping("/management/clubs/{club}/activities") Object create(HttpServletRequest req,@PathVariable long club,@RequestBody Map<String,Object> body) {
        long actor=user(req);
        return write(req,body,()->auth.requireRole(actor,"CLUB_MANAGER",club),()->{
            long id=activities.create(actor,club,body);
            return Writes.Result.created(activities.detail(actor,id,true),"/api/v1/management/activities/"+id);
        });
    }
    @GetMapping("/management/activities/{id}") Object managedDetail(HttpServletRequest req,@PathVariable long id) {
        var result=activities.detail(user(req),id,true); return ResponseEntity.ok().eTag(result.get("version").toString()).body(result);
    }
    @PatchMapping("/management/activities/{id}") Object edit(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body) {
        long actor=user(req); String etag=req.getHeader("If-Match");
        return write(req,Map.of("body",body,"etag",Objects.toString(etag,"")),managed(actor,id),()->{
            activities.edit(actor,id,body,etag); return Writes.Result.ok(activities.detail(actor,id,true));
        });
    }
    @PostMapping("/management/activities/{id}/submissions") Object submit(HttpServletRequest req,@PathVariable long id) {
        long actor=user(req);
        return write(req,Map.of(),managed(actor,id),()->{
            long sub=activities.submit(actor,id);
            return Writes.Result.created(activities.submission(actor,id,sub),"/api/v1/management/activities/"+id+"/submissions/"+sub);
        });
    }
    @GetMapping("/management/activity-submissions") Object pending(HttpServletRequest req,@RequestParam(defaultValue="PENDING") String status,@RequestParam(required=false) Long clubId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        long actor=user(req);
        require(!db.rows("SELECT id FROM role_grant WHERE account_id=? AND role='REVIEWER' AND status='ACTIVE'",actor).isEmpty(),403,"FORBIDDEN","需要审核权限");
        return db.page("SELECT s.id,s.activity_id FROM submission s JOIN activity a ON s.activity_id=a.id WHERE s.status=? AND (? IS NULL OR a.club_id=?) AND EXISTS(SELECT 1 FROM role_grant g WHERE g.account_id=? AND g.role='REVIEWER' AND g.scope_key=a.club_id AND g.status='ACTIVE') ORDER BY s.submitted_at,s.id",Arrays.asList(status,clubId,clubId,actor),page,pageSize,r->activities.submission(actor,number(r,"activity_id"),number(r,"id")));
    }
    @GetMapping("/management/activities/{id}/submissions") Object history(HttpServletRequest req,@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        long actor=user(req); activities.reader(actor,activities.activity(id,false));
        return db.page("SELECT id FROM submission WHERE activity_id=? ORDER BY submitted_at DESC,id",List.of(id),page,pageSize,r->activities.submission(actor,id,number(r,"id")));
    }
    @GetMapping("/management/activities/{id}/submissions/{sub}") Object submission(HttpServletRequest req,@PathVariable long id,@PathVariable long sub) { return activities.submission(user(req),id,sub); }
    @PutMapping("/management/activities/{id}/submissions/{sub}/review") Object review(HttpServletRequest req,@PathVariable long id,@PathVariable long sub,@RequestBody Map<String,Object> body) {
        long actor=user(req);
        return write(req,body,()->auth.requireRole(actor,"REVIEWER",number(activities.activity(id,false),"club_id")),()->Writes.Result.ok(activities.review(actor,id,sub,body)));
    }
    @PutMapping("/management/activities/{id}/cancellation") Object cancelActivity(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body) {
        long actor=user(req);
        return write(req,body,managed(actor,id),()->{ activities.cancelActivity(actor,id,body); return Writes.Result.ok(activities.detail(actor,id,true)); });
    }
    @GetMapping("/management/activities/{id}/registrations") Object roster(HttpServletRequest req,@PathVariable long id,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        long actor=user(req); activities.manager(actor,activities.activity(id,false));
        return db.page("SELECT id FROM registration WHERE activity_id=? AND (? IS NULL OR status=?) ORDER BY registered_at DESC,id",Arrays.asList(id,status,status),page,pageSize,r->activities.roster(id,number(r,"id")));
    }
    @PostMapping("/management/activities/{id}/registration-searches") Object searchRoster(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body) {
        long actor=user(req); activities.manager(actor,activities.activity(id,false));
        String field=Activities.text(body,"field",20);
        String keyword=Activities.text(body,"keyword",field.equals("NAME")?80:32);
        require(Set.of("NAME","STUDENT_NO").contains(field),400,"VALIDATION_FAILED","搜索字段不合法");
        String filter=field.equals("NAME")?"a.display_name LIKE ?":"a.student_no_digest=?";
        String value=field.equals("NAME")?"%"+keyword+"%":StudentNumbers.digest(keyword);
        return db.page("SELECT r.id FROM registration r JOIN account a ON a.id=r.student_id WHERE r.activity_id=? AND "+filter+" ORDER BY r.registered_at DESC,r.id",List.of(id,value),((Number)body.getOrDefault("page",1)).intValue(),((Number)body.getOrDefault("pageSize",20)).intValue(),r->activities.roster(id,number(r,"id")));
    }
    @PutMapping("/management/activities/{id}/registrations/{reg}/check-in") Object checkin(HttpServletRequest req,@PathVariable long id,@PathVariable long reg) {
        long actor=user(req); return write(req,Map.of(),managed(actor,id),()->Writes.Result.ok(activities.checkin(actor,id,reg)));
    }
    @GetMapping("/management/accounts") Object accounts(HttpServletRequest req,@RequestParam long accountId) {
        auth.requireRole(user(req),"SYSTEM_ADMIN",0);
        return db.rows("SELECT id AS account_id,display_name FROM account WHERE id=?",accountId).stream().map(Database::external).toList();
    }
    @GetMapping("/management/accounts/{account}/role-grants") Object grants(HttpServletRequest req,@PathVariable long account,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        auth.requireRole(user(req),"SYSTEM_ADMIN",0);
        return db.page("SELECT * FROM role_grant WHERE account_id=? AND (? IS NULL OR status=?) ORDER BY changed_at DESC,id",Arrays.asList(account,status,status),page,pageSize,this::grantView);
    }
    Map<String,Object> grantView(Map<String,Object> row) {
        var result=external(row); long scope=number(row,"scope_key"); result.remove("scopeKey"); result.put("scopeClubId",scope==0?null:Long.toString(scope)); return result;
    }
    @GetMapping("/management/accounts/{account}/role-grants/{grant}") Object grant(HttpServletRequest req,@PathVariable long account,@PathVariable long grant) {
        auth.requireRole(user(req),"SYSTEM_ADMIN",0); return grantView(db.one("SELECT * FROM role_grant WHERE id=? AND account_id=?",grant,account));
    }
    @PostMapping("/management/accounts/{account}/role-grants") Object grantRole(HttpServletRequest req,@PathVariable long account,@RequestBody Map<String,Object> body) {
        long actor=user(req);
        return write(req,body,()->auth.requireRole(actor,"SYSTEM_ADMIN",0),()->{
            db.one("SELECT id FROM account WHERE id=?",account);
            String role=Activities.text(body,"role",32);
            require(Set.of("CLUB_MANAGER","REVIEWER","SYSTEM_ADMIN").contains(role),400,"VALIDATION_FAILED","角色不合法");
            long scope=role.equals("SYSTEM_ADMIN")?0:Activities.id(body.get("scopeClubId"));
            if (scope!=0) db.one("SELECT id FROM club WHERE id=?",scope);
            var prior=db.rows("SELECT * FROM role_grant WHERE account_id=? AND role=? AND scope_key=? AND status='ACTIVE'",account,role,scope);
            if (!prior.isEmpty()) return Writes.Result.ok(grantView(prior.getFirst()));
            long id=db.insert("INSERT INTO role_grant(account_id,role,scope_key,changed_by,changed_at) VALUES(?,?,?,?,?)",account,role,scope,actor,Timestamp.from(db.now()));
            return Writes.Result.created(grantView(db.one("SELECT * FROM role_grant WHERE id=?",id)),"/api/v1/management/accounts/"+account+"/role-grants/"+id);
        });
    }
    @DeleteMapping("/management/accounts/{account}/role-grants/{grant}") Object revoke(HttpServletRequest req,@PathVariable long account,@PathVariable long grant) {
        long actor=user(req);
        return write(req,Map.of(),()->auth.requireRole(actor,"SYSTEM_ADMIN",0),()->{
            db.one("SELECT id FROM role_grant WHERE id=? AND account_id=?",grant,account);
            db.update("UPDATE role_grant SET status='REVOKED',changed_by=?,changed_at=? WHERE id=? AND status='ACTIVE'",actor,Timestamp.from(db.now()),grant);
            return new Writes.Result(204,null,null);
        });
    }
}
