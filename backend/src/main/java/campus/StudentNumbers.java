package campus;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StudentNumbers implements ApplicationRunner {
    final Database db;
    final Identity auth;
    static final String DEMO_KEY="course-demo-key-change-for-real-accounts";
    public StudentNumbers(Database db,Identity auth) { this.db=db; this.auth=auth; }
    static String digest(String number) {
        try {
            String key=System.getenv().getOrDefault("STUDENT_SEARCH_KEY",DEMO_KEY);
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(number.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    public void run(ApplicationArguments args) {
        if (auth.demo) {
            db.update("UPDATE account SET student_no_digest=? WHERE id=1",digest("DEMO0001"));
            db.update("UPDATE account SET student_no_digest=? WHERE id=2",digest("DEMO0002"));
        }
    }
}
