package kr.wsts.lms.global.support;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import kr.wsts.lms.global.error.AppException;

public final class Input {
    private Input() {}
    public static String str(Map<String,Object> input,String key,int min,int max) { Object value=input.get(key); if(!(value instanceof String text)||text.trim().length()<min||text.trim().length()>max)throw AppException.bad(key+" 항목은 "+min+"~"+max+"자로 입력해주세요."); return text.trim(); }
    public static String optional(Map<String,Object> input,String key,String fallback,int max) { return input.get(key)==null?fallback:str(input,key,0,max); }
    public static long id(Map<String,Object> input,String key) { try{ long n=new BigDecimal(String.valueOf(input.get(key))).longValueExact();if(n<=0)throw new ArithmeticException();return n;}catch(RuntimeException e){throw AppException.bad(key+" 식별자를 확인해주세요.");} }
    public static int integer(Map<String,Object> input,String key,int fallback,int min,int max) { if(input.get(key)==null)return fallback;try{int n=new BigDecimal(String.valueOf(input.get(key))).intValueExact();if(n<min||n>max)throw new ArithmeticException();return n;}catch(RuntimeException e){throw AppException.bad(key+" 값은 "+min+"~"+max+" 정수여야 합니다.");} }
    public static boolean bool(Map<String,Object> input,String key,boolean fallback) { Object v=input.get(key);if(v==null)return fallback;if(v instanceof Boolean b)return b;throw AppException.bad(key+" 값은 true/false여야 합니다."); }
    public static String choice(Map<String,Object> input,String key,String... values) { String v=str(input,key,1,100);if(!Arrays.asList(values).contains(v))throw AppException.bad(key+" 값은 "+String.join(", ",values)+" 중 하나여야 합니다.");return v; }
    public static LocalDate date(Map<String,Object> input,String key) { try{return LocalDate.parse(str(input,key,10,10));}catch(DateTimeException e){throw AppException.bad(key+" 날짜는 YYYY-MM-DD 형식이어야 합니다.");} }
    public static LocalDateTime dateTime(Map<String,Object> input,String key) { try{return OffsetDateTime.parse(str(input,key,20,40)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}catch(DateTimeException e){throw AppException.bad(key+" 시각에는 시간대 오프셋이 필요합니다. 예: 2026-11-09T09:00:00+09:00");} }
    public static LocalDateTime nullableDateTime(Map<String,Object> input,String key) { return input.get(key)==null?null:dateTime(input,key); }
    public static String url(Map<String,Object> input,String key,boolean required) { String v=required?str(input,key,1,2000):optional(input,key,"",2000);if(v.isBlank())return v;try{URI u=URI.create(v);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException();return v;}catch(IllegalArgumentException e){throw AppException.bad(key+" 항목은 인증정보 없는 HTTPS 주소여야 합니다.");} }
}
