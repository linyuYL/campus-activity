package campus;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
public class Database {
    final JdbcTemplate jdbc;
    public Database(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql,args);
    }
    public Map<String,Object> one(String sql, Object... args) {
        var rows = rows(sql,args);
        if (rows.isEmpty()) throw new ApiError(404,"NOT_FOUND","记录不存在");
        return rows.getFirst();
    }
    Map<String,Object> page(String sql,List<Object> args,int page,int size,java.util.function.Function<Map<String,Object>,Map<String,Object>> mapper) {
        ApiError.require(page>=1 && size>=1 && size<=100,400,"VALIDATION_FAILED","分页参数越界");
        long total=((Number)one("SELECT COUNT(*) AS total FROM ("+sql+") counted",args.toArray()).get("total")).longValue();
        var parameters=new ArrayList<>(args);
        parameters.add(size);
        parameters.add((long)(page-1)*size);
        var items=rows(sql+" LIMIT ? OFFSET ?",parameters.toArray()).stream().map(mapper).toList();
        return Map.of("items",items,"page",page,"pageSize",size,"total",total);
    }
    public int update(String sql, Object... args) { return jdbc.update(sql,args); }
    public long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i=0; i<args.length; i++) statement.setObject(i+1,args[i]);
            return statement;
        },holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    public Instant now() { return time(one("SELECT CURRENT_TIMESTAMP AS now").get("now")); }
    static Instant time(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
        return Instant.parse(value.toString());
    }
    static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    static Map<String,Object> external(Map<String,Object> row) {
        var result = new LinkedHashMap<String,Object>();
        row.forEach((key,value)->{
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char c : key.toCharArray()) {
                if (c=='_') { upper=true; continue; }
                name.append(upper ? Character.toUpperCase(c) : c);
                upper=false;
            }
            if (value instanceof Timestamp || value instanceof java.time.OffsetDateTime || value instanceof java.time.LocalDateTime) {
                value = time(value).atOffset(java.time.ZoneOffset.ofHours(8)).toString();
            } else if (key.equals("id") || key.endsWith("_id") || Set.of("created_by","cancelled_by","checked_in_by","changed_by","submitted_by","reviewed_by").contains(key)) {
                value = value == null ? null : value.toString();
            }
            if (!Set.of("active_marker","student_no_digest","token_hash").contains(key)) result.put(name.toString(),value);
        });
        return result;
    }
}
