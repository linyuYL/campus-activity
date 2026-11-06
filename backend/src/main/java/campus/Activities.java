package campus;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static campus.ApiError.require;
import static campus.Database.*;

@Service
public class Activities {
    final Database db;
    final Identity identity;
    public Activities(Database db,Identity identity) { this.db=db; this.identity=identity; }

    Map<String,Object> activity(long id,boolean lock) {
        return db.one("SELECT * FROM activity WHERE id=?"+(lock?" FOR UPDATE":""),id);
    }
    void manager(long actor,Map<String,Object> activity) {
        identity.requireRole(actor,"CLUB_MANAGER",number(activity,"club_id"));
    }
    void reader(long actor,Map<String,Object> activity) {
        long club=number(activity,"club_id");
        require(identity.hasRole(actor,"CLUB_MANAGER",club)||identity.hasRole(actor,"REVIEWER",club),403,"FORBIDDEN","无权查看该活动");
    }
    static String text(Map<String,Object> body,String key,int max) {
        Object value=body.get(key);
        require(value instanceof String,400,"VALIDATION_FAILED",key+"不能为空");
        String result=value.toString().strip();
        require(!result.isEmpty() && result.length()<=max,400,"VALIDATION_FAILED",key+"长度不合法");
        return result;
    }
    static long id(Object input) { return Long.parseLong(Objects.toString(input)); }
    static int positiveInt(Object input) {
        if (!(input instanceof Number value)) throw new IllegalArgumentException();
        long integer=value.longValue();
        require(value.doubleValue()==integer && integer>0 && integer<=Integer.MAX_VALUE,400,"VALIDATION_FAILED","请输入正整数");
        return (int)integer;
    }
    static Timestamp date(Object input) {
        require(input instanceof String,400,"VALIDATION_FAILED","时间必须含时区且不能为空");
        return Timestamp.from(OffsetDateTime.parse(input.toString()).toInstant());
    }
    void validate(Map<String,Object> body) {
        text(body,"title",100); text(body,"category",32); text(body,"location",200); text(body,"description",4000);
        positiveInt(body.get("capacity"));
        require(!db.rows("SELECT code FROM category WHERE code=?",body.get("category")).isEmpty(),400,"VALIDATION_FAILED","分类不存在");
        Instant closes=date(body.get("registrationClosesAt")).toInstant();
        Instant starts=date(body.get("startsAt")).toInstant();
        Instant ends=date(body.get("endsAt")).toInstant();
        require(closes.isBefore(starts)&&starts.isBefore(ends),400,"VALIDATION_FAILED","须满足报名截止早于开始、开始早于结束");
        if (body.get("posterRef")!=null) text(body,"posterRef",255);
    }
    long create(long actor,long club,Map<String,Object> body) {
        identity.requireRole(actor,"CLUB_MANAGER",club);
        validate(body);
        return db.insert("INSERT INTO activity(club_id,title,category,location,description,poster_ref,capacity,registration_closes_at,starts_at,ends_at,created_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            club,text(body,"title",100),body.get("category"),text(body,"location",200),text(body,"description",4000),body.get("posterRef"),positiveInt(body.get("capacity")),date(body.get("registrationClosesAt")),date(body.get("startsAt")),date(body.get("endsAt")),actor,Timestamp.from(db.now()));
    }
    Map<String,Object> summary(Map<String,Object> activity) {
        var result=external(activity);
        for (String key:List.of("description","createdBy","updatedAt","cancelledBy","cancelledAt","version")) result.remove(key);
        result.put("availableSeats","CANCELLED".equals(activity.get("status"))?0:number(activity,"capacity")-number(activity,"registered_count"));
        return result;
    }
    Map<String,Object> detail(long actor,long activityId,boolean managed) {
        var a=activity(activityId,false);
        if (managed) {
            reader(actor,a);
            var result=external(a);
            result.put("availableSeats","CANCELLED".equals(a.get("status"))?0:number(a,"capacity")-number(a,"registered_count"));
            var subs=db.rows("SELECT id FROM submission WHERE activity_id=? ORDER BY id DESC LIMIT 1",activityId);
            result.put("currentSubmissionId",subs.isEmpty()?null:subs.getFirst().get("id").toString());
            return result;
        }
        require(Set.of("PUBLISHED","CANCELLED").contains(a.get("status")),404,"NOT_FOUND","活动不存在");
        var result=summary(a);
        result.put("description",a.get("description"));
        var existing=db.rows("SELECT id FROM registration WHERE activity_id=? AND student_id=? AND active_marker=1",activityId,actor);
        String eligibility="OPEN";
        if ("CANCELLED".equals(a.get("status"))) eligibility="ACTIVITY_CANCELLED";
        else if (!existing.isEmpty()) eligibility="ALREADY_REGISTERED";
        else if (!db.now().isBefore(time(a.get("registration_closes_at")))) eligibility="CLOSED";
        else if (number(a,"capacity")==number(a,"registered_count")) eligibility="FULL";
        result.put("registrationEligibility",eligibility);
        result.put("myActiveRegistrationId",existing.isEmpty()?null:existing.getFirst().get("id").toString());
        return result;
    }
    void edit(long actor,long activityId,Map<String,Object> patch,String etag) {
        var a=activity(activityId,true);
        manager(actor,a);
        require(etag!=null,428,"PRECONDITION_REQUIRED","编辑需要 If-Match");
        require(etag.equals("\""+a.get("version")+"\""),412,"VERSION_MISMATCH","内容已更新，请刷新");
        require(Set.of("DRAFT","RETURNED").contains(a.get("status")),409,"INVALID_STATE","仅草稿或退回活动可编辑");
        Set<String> allowed=Set.of("title","category","location","description","posterRef","capacity","registrationClosesAt","startsAt","endsAt");
        require(allowed.containsAll(patch.keySet()),400,"VALIDATION_FAILED","包含不可编辑字段");
        var merged=external(a);
        merged.putAll(patch);
        validate(merged);
        db.update("UPDATE activity SET title=?,category=?,location=?,description=?,poster_ref=?,capacity=?,registration_closes_at=?,starts_at=?,ends_at=?,version=version+1,updated_at=? WHERE id=?",
            text(merged,"title",100),merged.get("category"),text(merged,"location",200),text(merged,"description",4000),merged.get("posterRef"),positiveInt(merged.get("capacity")),date(merged.get("registrationClosesAt")),date(merged.get("startsAt")),date(merged.get("endsAt")),Timestamp.from(db.now()),activityId);
    }
    long submit(long actor,long activityId) {
        var a=activity(activityId,true); manager(actor,a);
        require(Set.of("DRAFT","RETURNED").contains(a.get("status")),409,"INVALID_STATE","当前状态不能提审");
        long submissionId=db.insert("INSERT INTO submission(activity_id,submitted_by,submitted_at,status) VALUES(?,?,?,'PENDING')",activityId,actor,Timestamp.from(db.now()));
        db.update("UPDATE activity SET status='PENDING_REVIEW',version=version+1,updated_at=? WHERE id=?",Timestamp.from(db.now()),activityId);
        return submissionId;
    }
    Map<String,Object> submission(long actor,long activityId,long submissionId) {
        reader(actor,activity(activityId,false));
        var row=db.one("SELECT * FROM submission WHERE id=? AND activity_id=?",submissionId,activityId);
        var result=external(row);
        var reviews=db.rows("SELECT decision,comment,reviewed_by,reviewed_at FROM review WHERE submission_id=?",submissionId);
        result.put("review",reviews.isEmpty()?null:external(reviews.getFirst()));
        return result;
    }
    Map<String,Object> review(long actor,long activityId,long submissionId,Map<String,Object> body) {
        var a=activity(activityId,true);
        identity.requireRole(actor,"REVIEWER",number(a,"club_id"));
        String decision=text(body,"decision",16);
        require(Set.of("APPROVED","RETURNED").contains(decision),400,"VALIDATION_FAILED","审核决定不合法");
        String comment=body.get("comment")==null?null:body.get("comment").toString().strip();
        require(comment==null || comment.length()<=500,400,"VALIDATION_FAILED","审核意见过长");
        if (decision.equals("RETURNED")) require(comment!=null&&!comment.isBlank(),400,"VALIDATION_FAILED","退回原因必填");
        var sub=db.one("SELECT * FROM submission WHERE id=? AND activity_id=?",submissionId,activityId);
        if (!"PENDING".equals(sub.get("status"))) {
            var prior=db.one("SELECT * FROM review WHERE submission_id=?",submissionId);
            require(decision.equals(prior.get("decision"))&&Objects.equals(comment,prior.get("comment")),409,"INVALID_STATE","该审核单已有其他结论");
            return submission(actor,activityId,submissionId);
        }
        require("PENDING_REVIEW".equals(a.get("status")),409,"INVALID_STATE","活动已不在待审状态");
        Timestamp now=Timestamp.from(db.now());
        db.update("INSERT INTO review(submission_id,decision,comment,reviewed_by,reviewed_at) VALUES(?,?,?,?,?)",submissionId,decision,comment,actor,now);
        db.update("UPDATE submission SET status=? WHERE id=?",decision,submissionId);
        db.update("UPDATE activity SET status=?,published_at=CASE WHEN ?='APPROVED' THEN COALESCE(published_at,?) ELSE published_at END,updated_at=?,version=version+1 WHERE id=?",decision.equals("APPROVED")?"PUBLISHED":"RETURNED",decision,now,now,activityId);
        return submission(actor,activityId,submissionId);
    }
    void published(Map<String,Object> a) {
        require(!"CANCELLED".equals(a.get("status")),409,"ACTIVITY_CANCELLED","活动已取消");
        require("PUBLISHED".equals(a.get("status")),409,"ACTIVITY_NOT_PUBLISHED","活动尚未发布");
    }
    Map<String,Object> registration(long actor,long registrationId) {
        var r=db.one("SELECT * FROM registration WHERE id=? AND student_id=?",registrationId,actor);
        var a=activity(number(r,"activity_id"),false);
        var result=external(r);
        result.remove("studentId"); result.remove("activityId"); result.remove("checkedInBy");
        result.put("activity",summary(a));
        result.put("displayStatus","CANCELLED".equals(a.get("status"))?"ACTIVITY_CANCELLED":r.get("status"));
        result.put("canCancel","PUBLISHED".equals(a.get("status"))&&"REGISTERED".equals(r.get("status"))&&db.now().isBefore(time(a.get("registration_closes_at"))));
        return result;
    }
    record Signup(long id,boolean created) {}
    Signup signup(long actor,long activityId) {
        var a=activity(activityId,true); published(a);
        var existing=db.rows("SELECT id FROM registration WHERE activity_id=? AND student_id=? AND active_marker=1",activityId,actor);
        if (!existing.isEmpty()) return new Signup(number(existing.getFirst(),"id"),false);
        require(db.now().isBefore(time(a.get("registration_closes_at"))),409,"REGISTRATION_CLOSED","报名已截止");
        require(number(a,"registered_count")<number(a,"capacity"),409,"ACTIVITY_FULL","报名已满");
        long registrationId=db.insert("INSERT INTO registration(activity_id,student_id,registered_at) VALUES(?,?,?)",activityId,actor,Timestamp.from(db.now()));
        db.update("UPDATE activity SET registered_count=registered_count+1 WHERE id=?",activityId);
        return new Signup(registrationId,true);
    }
    Map<String,Object> cancelRegistration(long actor,long registrationId,Map<String,Object> body) {
        require(Boolean.TRUE.equals(body.get("confirmed")),400,"VALIDATION_FAILED","请确认取消");
        var original=db.one("SELECT activity_id FROM registration WHERE id=? AND student_id=?",registrationId,actor);
        var a=activity(number(original,"activity_id"),true);
        var r=db.one("SELECT * FROM registration WHERE id=? AND student_id=? FOR UPDATE",registrationId,actor);
        if ("CANCELLED".equals(r.get("status"))) return registration(actor,registrationId);
        published(a);
        require(db.now().isBefore(time(a.get("registration_closes_at"))),409,"REGISTRATION_CLOSED","已截止，不能取消");
        require("REGISTERED".equals(r.get("status")),409,"INVALID_STATE","已签到的报名不能取消");
        db.update("UPDATE registration SET status='CANCELLED',cancelled_at=? WHERE id=?",Timestamp.from(db.now()),registrationId);
        db.update("UPDATE activity SET registered_count=registered_count-1 WHERE id=?",a.get("id"));
        return registration(actor,registrationId);
    }
    void cancelActivity(long actor,long activityId,Map<String,Object> body) {
        require(Boolean.TRUE.equals(body.get("confirmed")),400,"VALIDATION_FAILED","请确认取消");
        var a=activity(activityId,true); manager(actor,a);
        if ("CANCELLED".equals(a.get("status"))) return;
        published(a);
        Timestamp now=Timestamp.from(db.now());
        db.update("UPDATE activity SET status='CANCELLED',cancelled_by=?,cancelled_at=?,updated_at=?,version=version+1 WHERE id=?",actor,now,now,activityId);
    }
    Map<String,Object> roster(long activityId,long registrationId) {
        return external(db.one("SELECT r.id AS registration_id,a.display_name AS student_name,a.student_no_last4,r.status,r.registered_at,r.cancelled_at,r.checked_in_at,r.checked_in_by FROM registration r JOIN account a ON a.id=r.student_id WHERE r.activity_id=? AND r.id=?",activityId,registrationId));
    }
    Map<String,Object> checkin(long actor,long activityId,long registrationId) {
        var a=activity(activityId,true); manager(actor,a); published(a);
        var r=db.one("SELECT * FROM registration WHERE id=? AND activity_id=? FOR UPDATE",registrationId,activityId);
        if ("CHECKED_IN".equals(r.get("status"))) return roster(activityId,registrationId);
        require("REGISTERED".equals(r.get("status")),409,"INVALID_STATE","只有有效报名可签到");
        Instant now=db.now();
        require(!now.isBefore(time(a.get("starts_at")).minusSeconds(1800))&&now.isBefore(time(a.get("ends_at"))),409,"CHECKIN_OUTSIDE_WINDOW","不在签到时间范围内");
        db.update("UPDATE registration SET status='CHECKED_IN',checked_in_at=?,checked_in_by=? WHERE id=?",Timestamp.from(now),actor,registrationId);
        return roster(activityId,registrationId);
    }
}
