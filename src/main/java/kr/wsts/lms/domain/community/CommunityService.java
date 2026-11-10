package kr.wsts.lms.domain.community;

import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.*;

@org.springframework.stereotype.Service
public class CommunityService {
    private final Db db; private final Actor actor; private final Notices notices;
    public CommunityService(Db db,Actor actor,Notices notices){this.db=db;this.actor=actor;this.notices=notices;}
    private Map<String,Object> post(long id){return db.one("SELECT p.*,u.name AS author_name FROM posts p JOIN users u ON u.id=p.author_id WHERE p.id=? AND p.deleted_at IS NULL",id);}


    public Map<String,Object> posts(long cohortId,String category,String q,int page,int size){
        actor.cohort(cohortId);if(q.length()>100)throw AppException.bad("검색어는 100자 이하로 입력해주세요.");
        var args=new ArrayList<Object>();args.add(cohortId);String where=" FROM posts p JOIN users u ON u.id=p.author_id WHERE p.cohort_id=? AND p.deleted_at IS NULL";
        if(!category.isBlank()){if(!Set.of("COURSE","SYSTEM","QUESTION","FAQ").contains(category))throw AppException.bad("게시판 분류를 확인해주세요.");where+=" AND p.category=?";args.add(category);}
        if(!q.isBlank()){where+=" AND (p.title LIKE ? OR p.body LIKE ?)";args.add("%"+q+"%");args.add("%"+q+"%");}
        return db.page("SELECT p.id,p.cohort_id,p.author_id,u.name AS author_name,p.category,p.title,p.pinned,p.views,p.created_at,p.updated_at"+where+" ORDER BY p.pinned DESC,p.id DESC","SELECT COUNT(*)"+where,page,size,args.toArray());
    }
    @Transactional
    public Map<String,Object> create(long cohortId,Map<String,Object> input){
        actor.requireAdmin();actor.cohort(cohortId);String category=Input.choice(input,"category","COURSE","SYSTEM","QUESTION","FAQ");boolean pinned=Input.bool(input,"pinned",false);

        long id=db.insert("INSERT INTO posts(cohort_id,author_id,category,title,body,pinned,views,created_at,updated_at) VALUES(?,?,?,?,?,?,0,?,?)",cohortId,actor.id(),category,Input.str(input,"title",1,160),Input.str(input,"body",1,20000),pinned,Db.now(),Db.now());
        if(!category.equals("QUESTION"))notices.cohort(cohortId,actor.id(),"POST","새 공지사항",Input.str(input,"title",1,160),"POST",id);
        return post(id);
    }
    @Transactional
    public Map<String,Object> get(long postId){var p=post(postId);actor.cohort(Db.id(p,"cohortId"));db.update("UPDATE posts SET views=views+1 WHERE id=?",postId);var result=post(postId);result.put("files",db.list("SELECT id,original_name,content_type,size_bytes,created_at FROM files WHERE post_id=? AND deleted_at IS NULL ORDER BY id",postId));return result;}
    @Transactional
    public Map<String,Object> update(long postId,Map<String,Object> input){
        actor.requireAdmin();var p=post(postId);String category=input.containsKey("category")?Input.choice(input,"category","COURSE","SYSTEM","QUESTION","FAQ"):(String)p.get("category");
        boolean pinned=Input.bool(input,"pinned",Boolean.TRUE.equals(p.get("pinned")));

        db.update("UPDATE posts SET title=?,body=?,category=?,pinned=?,updated_at=? WHERE id=?",input.containsKey("title")?Input.str(input,"title",1,160):p.get("title"),input.containsKey("body")?Input.str(input,"body",1,20000):p.get("body"),category,pinned,Db.now(),postId);
        return post(postId);
    }
    @Transactional
    public void delete(long postId){actor.requireAdmin();var p=post(postId);db.update("UPDATE posts SET deleted_at=?,updated_at=? WHERE id=?",Db.now(),Db.now(),postId);}

    public Map<String,Object> comments(long postId,int page,int size){var p=post(postId);actor.cohort(Db.id(p,"cohortId"));return db.page("SELECT c.id,c.post_id,c.author_id,u.name AS author_name,c.body,c.created_at,c.updated_at FROM comments c JOIN users u ON u.id=c.author_id WHERE c.post_id=? AND c.deleted_at IS NULL ORDER BY c.id","SELECT COUNT(*) FROM comments WHERE post_id=? AND deleted_at IS NULL",page,size,postId);}
    @Transactional
    public Map<String,Object> comment(long postId,Map<String,Object> input){var p=post(postId);long cohort=Db.id(p,"cohortId");actor.cohort(cohort);long id=db.insert("INSERT INTO comments(post_id,author_id,body,created_at,updated_at) VALUES(?,?,?,?,?)",postId,actor.id(),Input.str(input,"body",1,5000),Db.now(),Db.now());if(Db.id(p,"authorId")!=actor.id())notices.user(Db.id(p,"authorId"),cohort,"COMMENT","게시글에 댓글이 달렸습니다.",(String)p.get("title"),"POST",postId);return db.one("SELECT id,post_id,author_id,body,created_at,updated_at FROM comments WHERE id=?",id);}
    private Map<String,Object> ownComment(long id){var c=db.one("SELECT * FROM comments WHERE id=? AND deleted_at IS NULL",id);var p=post(Db.id(c,"postId"));long cohort=Db.id(p,"cohortId");actor.cohort(cohort);if(Db.id(c,"authorId")!=actor.id()&&!actor.admin())throw AppException.forbidden();return c;}
    @Transactional
    public Map<String,Object> editComment(long commentId,Map<String,Object> input){ownComment(commentId);db.update("UPDATE comments SET body=?,updated_at=? WHERE id=?",Input.str(input,"body",1,5000),Db.now(),commentId);return db.one("SELECT id,post_id,author_id,body,created_at,updated_at FROM comments WHERE id=?",commentId);}
    @Transactional
    public void deleteComment(long commentId){ownComment(commentId);db.update("UPDATE comments SET deleted_at=?,updated_at=? WHERE id=?",Db.now(),Db.now(),commentId);}
}
