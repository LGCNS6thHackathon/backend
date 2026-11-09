package kr.wsts.lms.domain.integration;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.*;
@RestController @RequestMapping("/api")
public class DeliveryController {
    private final Db db;private final Actor actor;private final String webhook;private final String publicBase;
    public DeliveryController(Db db,Actor actor,@Value("${app.discord.webhook-url:}")String webhook,@Value("${app.public-base-url:http://localhost:3000}")String publicBase){this.db=db;this.actor=actor;this.webhook=webhook;this.publicBase=publicBase;}
    @GetMapping("/public/notices/{slug}")
    public Map<String,Object> publicNotice(@PathVariable String slug){return db.one("SELECT title,body,created_at FROM posts WHERE public_slug=? AND visibility='PUBLIC' AND deleted_at IS NULL AND category IN ('COURSE','SYSTEM')",slug);}
    @PostMapping("/posts/{id}/discord") @Transactional
    public Map<String,Object> send(@PathVariable long id){
        var post=db.one("SELECT * FROM posts WHERE id=? AND deleted_at IS NULL FOR UPDATE",id);actor.staff(Db.id(post,"cohortId"));
        if(!Set.of("COURSE","SYSTEM").contains(post.get("category"))||!"PUBLIC".equals(post.get("visibility"))||post.get("publicSlug")==null)throw AppException.bad("공개 링크가 있는 공지만 전송할 수 있습니다.");
        var old=db.find("SELECT * FROM notice_deliveries WHERE post_id=? ORDER BY id DESC LIMIT 1",id);
        if(old.isPresent()&&Set.of("SENT","PENDING").contains(old.get().get("status")))throw AppException.conflict("전송 완료 또는 전송 중인 공지입니다. 중복 전송을 차단했습니다.");
        String title=(String)post.get("title"),text=(String)post.get("body"),summary=text.substring(0,Math.min(text.length(),700)),url=publicBase.replaceAll("/$","")+"/public/notices/"+post.get("publicSlug");
        long delivery=old.isPresent()?Db.id(old.get(),"id"):db.insert("INSERT INTO notice_deliveries(post_id,requested_by,channel,status,idempotency_key,title_snapshot,summary_snapshot,public_url,attempt_count,created_at) VALUES(?,?,'DISCORD','PENDING',?,?,?,?,0,?)",id,actor.id(),"post-"+id,title,summary,url,Db.now());
        db.update("UPDATE notice_deliveries SET status='PENDING',attempt_count=attempt_count+1,error_code=NULL WHERE id=?",delivery);
        String error=null;String status="SENT";
        try{
            URI uri=URI.create(webhook);if(!"https".equals(uri.getScheme())||!Set.of("discord.com","discordapp.com").contains(uri.getHost())||!uri.getPath().startsWith("/api/webhooks/"))throw new IllegalArgumentException();
            var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(5));factory.setReadTimeout(Duration.ofSeconds(10));
            RestClient.builder().requestFactory(factory).build().post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(Map.of("content",title+"\n"+summary+"\n"+url,"allowed_mentions",Map.of("parse",List.of()))).retrieve().toBodilessEntity();
        }catch(IllegalArgumentException e){error="DISCORD_NOT_CONFIGURED";status="FAILED";}catch(org.springframework.web.client.HttpClientErrorException e){error="DELIVERY_REJECTED";status="FAILED";}catch(Exception e){error="DELIVERY_UNKNOWN";status="PENDING";}
        db.update("UPDATE notice_deliveries SET status=?,error_code=?,sent_at=? WHERE id=?",status,error,"SENT".equals(status)?Db.now():null,delivery);
        return db.one("SELECT id,post_id,status,attempt_count,error_code,sent_at FROM notice_deliveries WHERE id=?",delivery);
    }
}
