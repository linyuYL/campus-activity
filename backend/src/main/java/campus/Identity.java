package campus;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Identity {
    final Database db;
    final boolean demo;
    public Identity(Database db,@Value("${campus.demo-login:false}") boolean demo) {
        this.db=db;
        this.demo=demo;
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    long actor(HttpServletRequest request) {
        String header=request.getHeader("Authorization");
        ApiError.require(header!=null && header.startsWith("Bearer "),401,"UNAUTHENTICATED","请先登录");
        var sessions=db.rows("SELECT account_id FROM api_session WHERE token_hash=? AND expires_at>CURRENT_TIMESTAMP",hash(header.substring(7)));
        ApiError.require(!sessions.isEmpty(),401,"UNAUTHENTICATED","登录已失效");
        return Database.number(sessions.getFirst(),"account_id");
    }
    Map<String,Object> login(long accountId) {
        ApiError.require(demo,403,"FORBIDDEN","当前部署关闭演示登录");
        db.one("SELECT id FROM account WHERE id=?",accountId);
        byte[] bytes=new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token=HexFormat.of().formatHex(bytes);
        Instant expiry=db.now().plusSeconds(28800);
        db.update("INSERT INTO api_session(token_hash,account_id,expires_at) VALUES(?,?,?)",hash(token),accountId,Timestamp.from(expiry));
        return Map.of("accessToken",token,"expiresAt",expiry.toString());
    }
    void requireRole(long actor,String role,long club) {
        var grants=db.rows("SELECT id FROM role_grant WHERE account_id=? AND role=? AND scope_key=? AND status='ACTIVE'",actor,role,club);
        ApiError.require(!grants.isEmpty(),403,"FORBIDDEN","你没有权限访问该资源");
    }
    boolean hasRole(long actor,String role,long club) {
        return !db.rows("SELECT id FROM role_grant WHERE account_id=? AND role=? AND scope_key=? AND status='ACTIVE'",actor,role,club).isEmpty();
    }
}
