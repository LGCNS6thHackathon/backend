package kr.wsts.lms.domain.integration;

import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.*;

@RestController
@RequestMapping("/api")
public class LearningAssetsController {
    private final Db db; private final Actor actor;
    public LearningAssetsController(Db db, Actor actor){this.db=db;this.actor=actor;}
    private boolean staff(long cohort){var e=actor.cohort(cohort);return actor.admin()||Set.of("INSTRUCTOR","MANAGER").contains(e.get("role"));}
    private String table(String kind){return switch(kind){case "subjects"->"learning_subjects";case "units"->"learning_units";case "topics"->"learning_topics";case "videos"->"lecture_videos";case "chapters"->"lecture_chapters";default->throw AppException.notFound();};}
    private Map<String,Object> row(String kind,long id){return switch(kind){
        case "subjects","videos"->db.one("SELECT * FROM "+table(kind)+" WHERE id=? AND deleted_at IS NULL",id);
        case "units"->db.one("SELECT u.*,s.cohort_id FROM learning_units u JOIN learning_subjects s ON s.id=u.subject_id WHERE u.id=? AND u.deleted_at IS NULL AND s.deleted_at IS NULL",id);
        case "topics"->db.one("SELECT t.*,s.cohort_id FROM learning_topics t JOIN learning_units u ON u.id=t.unit_id JOIN learning_subjects s ON s.id=u.subject_id WHERE t.id=? AND t.deleted_at IS NULL AND u.deleted_at IS NULL AND s.deleted_at IS NULL",id);
        case "chapters"->db.one("SELECT c.*,v.cohort_id,v.visibility,v.youtube_url,v.lecture_date,v.period_no FROM lecture_chapters c JOIN lecture_videos v ON v.id=c.video_id WHERE c.id=? AND v.deleted_at IS NULL",id);
        default->throw AppException.notFound();};}
    public void topic(Long id,long cohort){if(id!=null&&Db.id(row("topics",id),"cohortId")!=cohort)throw AppException.bad("같은 기수의 핵심 개념을 선택해주세요.");}
    private Long optionalId(Map<String,Object> body,String key){return body.get(key)==null?null:Input.id(body,key);}


    @GetMapping("/cohorts/{cohortId}/{kind:subjects|units|topics|videos|chapters}")
    public Map<String,Object> list(@PathVariable long cohortId,@PathVariable String kind,@RequestParam(required=false)Long videoId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="100")int size){
        boolean staff=staff(cohortId);String from;String select;
        switch(kind){
            case "subjects"->{select="s.*";from=" FROM learning_subjects s WHERE s.cohort_id=? AND s.deleted_at IS NULL";}
            case "units"->{select="u.*,s.cohort_id";from=" FROM learning_units u JOIN learning_subjects s ON s.id=u.subject_id WHERE s.cohort_id=? AND s.deleted_at IS NULL AND u.deleted_at IS NULL";}
            case "topics"->{select="t.*,s.cohort_id";from=" FROM learning_topics t JOIN learning_units u ON u.id=t.unit_id JOIN learning_subjects s ON s.id=u.subject_id WHERE s.cohort_id=? AND s.deleted_at IS NULL AND u.deleted_at IS NULL AND t.deleted_at IS NULL";}
            case "videos"->{select="v.*";from=" FROM lecture_videos v WHERE v.cohort_id=? AND v.deleted_at IS NULL"+(staff?"":" AND v.visibility='PUBLIC'");}
            default->{select="c.*,v.cohort_id,v.youtube_url,v.lecture_date,v.period_no";from=" FROM lecture_chapters c JOIN lecture_videos v ON v.id=c.video_id WHERE v.cohort_id=? AND v.deleted_at IS NULL"+(staff?"":" AND v.visibility='PUBLIC'");}
        }
        List<Object> args=new ArrayList<>(List.of(cohortId));if(kind.equals("chapters")&&videoId!=null){from+=" AND c.video_id=?";args.add(videoId);}
        return db.page("SELECT "+select+from+" ORDER BY 1","SELECT COUNT(*)"+from,page,size,args.toArray());
    }
    @PostMapping("/cohorts/{cohortId}/{kind:subjects|units|topics|videos|chapters}")
    @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> create(@PathVariable long cohortId,@PathVariable String kind,@RequestBody Map<String,Object> body){actor.staff(cohortId);db.one("SELECT id FROM cohorts WHERE id=? FOR UPDATE",cohortId);return save(kind,0,cohortId,body);}
    @PatchMapping("/{kind:subjects|units|topics|videos|chapters}/{id}") @Transactional
    public Map<String,Object> update(@PathVariable String kind,@PathVariable long id,@RequestBody Map<String,Object> body){var old=row(kind,id);long cohort=Db.id(old,"cohortId");actor.staff(cohort);var merged=new LinkedHashMap<>(old);merged.putAll(body);return save(kind,id,cohort,merged);}
    private Map<String,Object> save(String kind,long id,long cohort,Map<String,Object> body){
        String title=Input.str(body,"title",1,160);var now=Db.now();
        if(kind.equals("videos")){
            String url=Input.url(body,"youtubeUrl",true);String youtube=youtube(url);var date=Input.date(body,"lectureDate");int period=Input.integer(body,"periodNo",1,1,100);String visibility=Input.choice(body,"visibility","PUBLIC","PRIVATE");
            if(id==0)id=db.insert("INSERT INTO lecture_videos(cohort_id,created_by,title,youtube_url,youtube_video_id,lecture_date,period_no,visibility,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",cohort,actor.id(),title,url,youtube,date,period,visibility,now,now);
            else db.update("UPDATE lecture_videos SET title=?,youtube_url=?,youtube_video_id=?,lecture_date=?,period_no=?,visibility=?,updated_at=? WHERE id=?",title,url,youtube,date,period,visibility,now,id);
        }else if(kind.equals("chapters")){
            long video=Input.id(body,"videoId");if(Db.id(row("videos",video),"cohortId")!=cohort)throw AppException.bad("같은 기수의 영상을 선택해주세요.");Long topic=optionalId(body,"topicId");topic(topic,cohort);int start=Input.integer(body,"startSeconds",0,0,Integer.MAX_VALUE);
            if(id==0)id=db.insert("INSERT INTO lecture_chapters(video_id,topic_id,title,start_seconds,position,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",video,topic,title,start,db.count("SELECT COALESCE(MAX(position),0)+1 FROM lecture_chapters WHERE video_id=?",video),now,now);
            else db.update("UPDATE lecture_chapters SET title=?,topic_id=?,start_seconds=?,updated_at=? WHERE id=?",title,topic,start,now,id);
        }else{
            String parent=kind.equals("subjects")?"cohort_id":kind.equals("units")?"subject_id":"unit_id";long parentId=kind.equals("subjects")?cohort:Input.id(body,kind.equals("units")?"subjectId":"unitId");
            if(!kind.equals("subjects")&&Db.id(row(kind.equals("units")?"subjects":"units",parentId),"cohortId")!=cohort)throw AppException.bad("같은 기수의 상위 항목을 선택해주세요.");
            String description=Input.optional(body,"description","",10000);
            if(id==0){long position=db.count("SELECT COALESCE(MAX(position),0)+1 FROM "+table(kind)+" WHERE "+parent+"=?",parentId);id=kind.equals("topics")?db.insert("INSERT INTO learning_topics(unit_id,title,description,position,created_at,updated_at) VALUES(?,?,?,?,?,?)",parentId,title,description,position,now,now):db.insert("INSERT INTO "+table(kind)+"("+parent+",title,position,created_at,updated_at) VALUES(?,?,?,?,?)",parentId,title,position,now,now);}
            else if(kind.equals("topics"))db.update("UPDATE learning_topics SET title=?,description=?,updated_at=? WHERE id=?",title,description,now,id);
            else db.update("UPDATE "+table(kind)+" SET title=?,updated_at=? WHERE id=?",title,now,id);
        }
        return row(kind,id);
    }
    static String youtube(String value){try{URI u=URI.create(value);String id=null;if(Set.of("youtube.com","www.youtube.com").contains(u.getHost())&&"/watch".equals(u.getPath())){for(String q:Objects.toString(u.getQuery(),"").split("&"))if(q.startsWith("v="))id=q.substring(2);}else if("youtu.be".equals(u.getHost()))id=u.getPath().substring(1);if(id==null||!id.matches("[A-Za-z0-9_-]{11}"))throw new IllegalArgumentException();return id;}catch(RuntimeException e){throw AppException.bad("지원하는 YouTube URL을 입력해주세요.");}}
    @DeleteMapping("/{kind:subjects|units|topics|videos|chapters}/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void delete(@PathVariable String kind,@PathVariable long id){var old=row(kind,id);actor.staff(Db.id(old,"cohortId"));
        if(kind.equals("subjects")&&db.count("SELECT COUNT(*) FROM learning_units WHERE subject_id=? AND deleted_at IS NULL",id)>0||kind.equals("units")&&db.count("SELECT COUNT(*) FROM learning_topics WHERE unit_id=? AND deleted_at IS NULL",id)>0)throw AppException.conflict("하위 항목을 먼저 삭제해주세요.");
        if(kind.equals("chapters")){db.update("DELETE FROM assessment_question_review_links WHERE chapter_id=?",id);db.update("DELETE FROM lecture_chapters WHERE id=?",id);}
        else db.update("UPDATE "+table(kind)+" SET deleted_at=?,updated_at=? WHERE id=?",Db.now(),Db.now(),id);
    }
}
