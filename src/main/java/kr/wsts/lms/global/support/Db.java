package kr.wsts.lms.global.support;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import kr.wsts.lms.global.error.AppException;

@Component
public class Db {
    private final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public static LocalDateTime now() { return LocalDateTime.now(Clock.systemUTC()); }
    public static long id(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public List<Map<String,Object>> list(String sql,Object... args) { return jdbc.query(sql,(rs,n)->map(rs),args); }
    public Optional<Map<String,Object>> find(String sql,Object... args) { return list(sql,args).stream().findFirst(); }
    public Map<String,Object> one(String sql,Object... args) { return find(sql,args).orElseThrow(AppException::notFound); }
    public long count(String sql,Object... args) { Long value=jdbc.queryForObject(sql,Long.class,args); return value==null?0:value; }
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    public long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{ var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]); return statement; },key);
        Number value=key.getKey(); if(value==null)throw new IllegalStateException("No generated key"); return value.longValue();
    }
    public Map<String,Object> page(String select,String countSql,int page,int size,Object... args) {
        if(page<0||size<1||size>100)throw AppException.bad("page는 0 이상, size는 1~100이어야 합니다.");
        var parameters=new ArrayList<>(Arrays.asList(args));parameters.add(size);parameters.add((long)page*size);
        return Map.of("items",list(select+" LIMIT ? OFFSET ?",parameters.toArray()),"page",page,"size",size,"total",count(countSql,args));
    }
    private static Map<String,Object> map(ResultSet rs)throws SQLException {
        var row=new LinkedHashMap<String,Object>(); var meta=rs.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++) {
            String raw=meta.getColumnLabel(i); String name=raw.equals(raw.toUpperCase(Locale.ROOT))?raw.toLowerCase(Locale.ROOT):raw;
            var parts=name.split("_"); var key=new StringBuilder(parts[0]); for(int j=1;j<parts.length;j++)if(!parts[j].isEmpty())key.append(Character.toUpperCase(parts[j].charAt(0))).append(parts[j].substring(1));
            Object value=rs.getObject(i);
            if(value instanceof Timestamp t)value=t.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
            else if(value instanceof java.sql.Date d)value=d.toLocalDate().toString();
            else if(value instanceof Time t)value=t.toLocalTime().toString();
            else if(value instanceof LocalDateTime d)value=d.toInstant(ZoneOffset.UTC).toString();
            else if(value instanceof LocalDate d)value=d.toString();
            if((name.equals("pinned")||name.equals("is_correct"))&&value instanceof Number n)value=n.intValue()!=0;
            row.put(key.toString(),value);
        }
        return row;
    }
}
