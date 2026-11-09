package kr.wsts.lms.global.support;

import org.springframework.stereotype.Component;

@Component
public class Notices {
    private final Db db;
    public Notices(Db db) { this.db=db; }
    public void user(long userId,Long cohortId,String type,String title,String body,String resource,long resourceId) {
        db.insert("INSERT INTO notifications(user_id,cohort_id,type,title,body,resource_type,resource_id,created_at) VALUES(?,?,?,?,?,?,?,?)",userId,cohortId,type,title,body,resource,resourceId,Db.now());
    }
    public void cohort(long cohortId,long exceptUser,String type,String title,String body,String resource,long resourceId) {
        for(var row:db.list("SELECT user_id FROM enrollments WHERE cohort_id=? AND status='ACTIVE' AND user_id<>?",cohortId,exceptUser))user(Db.id(row,"userId"),cohortId,type,title,body,resource,resourceId);
    }
}
