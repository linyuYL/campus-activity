package campus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.PessimisticLockingFailureException;

@Service
public class Writes {
    record Result(int status,Object body,String location) {
        static Result ok(Object body) { return new Result(200,body,null); }
        static Result created(Object body,String location) { return new Result(201,body,location); }
    }
    final Database db;
    final TransactionTemplate tx;
    final ObjectMapper json;
    public Writes(Database db,TransactionTemplate tx,ObjectMapper json) { this.db=db; this.tx=tx; this.json=json; }
    Result execute(long actor,String operation,String key,Object body,Runnable authorize,Supplier<Result> action) {
        ApiError.require(key!=null&&!key.isBlank()&&key.length()<=128,400,"VALIDATION_FAILED","写操作需要 Idempotency-Key");
        try {
            String bodyHash=Identity.hash(json.writeValueAsString(body));
            for (int attempt=0; ; attempt++) {
                try {
                    return tx.execute(status->{
                        // 同一账号的写请求串行，跨账号仍可并发竞争活动名额。
                        db.one("SELECT id FROM account WHERE id=? FOR UPDATE",actor);
                        authorize.run();
                        var prior=db.rows("SELECT * FROM idempotency WHERE actor_id=? AND operation=? AND request_key=?",actor,operation,key);
                        if (!prior.isEmpty()) {
                            var entry=prior.getFirst();
                            ApiError.require(bodyHash.equals(entry.get("body_hash")),409,"IDEMPOTENCY_KEY_REUSED","操作键已对应其他内容");
                            try {
                                Object saved=json.readValue(entry.get("response_json").toString(),new TypeReference<Object>(){});
                                return new Result(((Number)entry.get("response_status")).intValue(),saved,(String)entry.get("response_location"));
                            } catch (Exception error) { throw new IllegalStateException(error); }
                        }
                        Result result=action.get();
                        try {
                            db.update("INSERT INTO idempotency(actor_id,operation,request_key,body_hash,response_json,response_status,response_location,created_at) VALUES(?,?,?,?,?,?,?,?)",actor,operation,key,bodyHash,json.writeValueAsString(result.body()),result.status(),result.location(),Timestamp.from(db.now()));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
                        db.update("INSERT INTO audit_log(actor_id,operation,occurred_at) VALUES(?,?,?)",actor,operation,Timestamp.from(db.now()));
                        return result;
                    });
                } catch (PessimisticLockingFailureException error) {
                    if (attempt>=2) throw error;
                    // 事务已退出，连接已归还，退避不占用数据库连接。
                    Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(26 << attempt));
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiError(503,"TEMPORARILY_UNAVAILABLE","请求被中断，请查询结果后重试");
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException(error); }
    }
}
