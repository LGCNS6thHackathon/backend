package kr.wsts.lms.domain.integration;
import java.util.*;
import org.springframework.stereotype.Component;
import kr.wsts.lms.global.support.*;
import kr.wsts.lms.global.error.AppException;
@Component
public class QuestionLinks {
    private final Db db;public QuestionLinks(Db db){this.db=db;}
    public void save(long question,Map<String,Object> body){
        long cohort=Db.id(db.one("SELECT a.cohort_id FROM assessment_questions q JOIN assessments a ON a.id=q.assessment_id WHERE q.id=?",question),"cohortId");
        if(body.containsKey("topicId")){
            Long topic=body.get("topicId")==null?null:Input.id(body,"topicId");
            if(topic!=null&&db.count("SELECT COUNT(*) FROM learning_topics t JOIN learning_units u ON u.id=t.unit_id JOIN learning_subjects s ON s.id=u.subject_id WHERE t.id=? AND s.cohort_id=? AND t.deleted_at IS NULL AND u.deleted_at IS NULL AND s.deleted_at IS NULL",topic,cohort)!=1)throw AppException.bad("같은 기수의 핵심 개념을 선택해주세요.");
            db.update("UPDATE assessment_questions SET topic_id=? WHERE id=?",topic,question);
        }
        if(body.containsKey("chapterId")){
            Long chapter=body.get("chapterId")==null?null:Input.id(body,"chapterId");
            if(chapter!=null&&db.count("SELECT COUNT(*) FROM lecture_chapters c JOIN lecture_videos v ON v.id=c.video_id WHERE c.id=? AND v.cohort_id=? AND v.deleted_at IS NULL",chapter,cohort)!=1)throw AppException.bad("같은 기수의 복습 챕터를 선택해주세요.");
            clear(question);if(chapter!=null)db.insert("INSERT INTO assessment_question_review_links(question_id,chapter_id,position) VALUES(?,?,1)",question,chapter);
        }
    }
    public void clear(long question){db.update("DELETE FROM assessment_question_review_links WHERE question_id=?",question);}
    public void decorate(Map<String,Object> question,boolean reveal){
        if(!reveal)return;
        var links=db.list("SELECT l.id,c.id AS chapter_id,c.title,c.start_seconds,v.lecture_date,v.period_no,v.youtube_url FROM assessment_question_review_links l JOIN lecture_chapters c ON c.id=l.chapter_id JOIN lecture_videos v ON v.id=c.video_id WHERE l.question_id=? AND v.deleted_at IS NULL AND v.visibility='PUBLIC' ORDER BY l.position",Db.id(question,"id"));
        question.put("reviewLinks",links);question.put("chapterId",links.isEmpty()?null:links.get(0).get("chapterId"));
    }
}
