package kr.wsts.lms.domain.lecture;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.wsts.lms.domain.lecture.repository.JdbcLectureVideoRepository;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;
import kr.wsts.lms.global.support.Input;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LectureVideoService {
    private final JdbcLectureVideoRepository repository;
    private final Actor actor;
    public LectureVideoService(JdbcLectureVideoRepository repository, Actor actor) {
        this.repository=repository; this.actor=actor;
    }

    public Map<String,Object> list(long cohortId, boolean admin, int page, int size) {
        if (admin) actor.requireAdmin();
        actor.cohort(cohortId);
        return repository.list(cohortId,!admin,page,size);
    }

    public Map<String,Object> get(long id, boolean admin) {
        if (admin) actor.requireAdmin();
        var video=repository.find(id,false);
        actor.cohort(Db.id(video,"cohortId"));
        if (!admin && !"PUBLIC".equals(video.get("visibility"))) throw AppException.notFound();
        return video;
    }

    @Transactional
    public Map<String,Object> create(long cohortId, Map<String,Object> input) {
        actor.requireAdmin(); actor.cohort(cohortId);
        Values v=values(input);
        try {
            long id=repository.create(cohortId,actor.id(),v.title,v.url,v.videoId,v.date,v.period,v.duration,v.visibility);
            return repository.find(id,false);
        } catch (DuplicateKeyException e) { throw duplicatePeriod(); }
    }

    @Transactional
    public Map<String,Object> update(long id, Map<String,Object> input) {
        actor.requireAdmin();
        var old=repository.find(id,true);
        var merged=new LinkedHashMap<String,Object>(old);
        // Resource identity, cohort and author are immutable.
        for (String key : new String[]{"title","youtubeUrl","lectureDate","periodNo","durationSeconds","visibility"}) {
            if (input.containsKey(key)) merged.put(key,input.get(key));
        }
        Values v=values(merged);
        try {
            repository.update(id,v.title,v.url,v.videoId,v.date,v.period,v.duration,v.visibility);
            return repository.find(id,false);
        } catch (DuplicateKeyException e) { throw duplicatePeriod(); }
    }

    @Transactional
    public void delete(long id) {
        actor.requireAdmin(); repository.find(id,true); repository.delete(id);
    }

    private Values values(Map<String,Object> input) {
        String title=Input.str(input,"title",1,160);
        String url=Input.str(input,"youtubeUrl",1,2000);
        var date=Input.date(input,"lectureDate");
        if (input.get("periodNo")==null) throw AppException.bad("periodNo는 필수입니다.");
        int period=Input.integer(input,"periodNo",1,1,Integer.MAX_VALUE);
        Integer duration=input.get("durationSeconds")==null?null:Input.integer(input,"durationSeconds",1,1,Integer.MAX_VALUE);
        String visibility=input.containsKey("visibility")?Input.choice(input,"visibility","PUBLIC","PRIVATE"):"PRIVATE";
        return new Values(title,url,YouTubeLink.videoId(url),date,period,duration,visibility);
    }

    private AppException duplicatePeriod() {
        return AppException.conflict("같은 기수·날짜·교시의 영상이 이미 존재합니다. 삭제한 영상의 교시도 재사용할 수 없습니다.");
    }
    private record Values(String title,String url,String videoId,java.time.LocalDate date,int period,Integer duration,String visibility) {}
}
