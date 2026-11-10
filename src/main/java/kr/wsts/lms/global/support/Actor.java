package kr.wsts.lms.global.support;

import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import kr.wsts.lms.global.error.AppException;

@Component
public class Actor {
    private final Db db;
    public Actor(Db db) { this.db=db; }
    public long id() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        try { if(authentication==null||!authentication.isAuthenticated())throw new NumberFormatException(); return Long.parseLong(authentication.getName()); }
        catch(NumberFormatException e) { throw new AppException(401,"UNAUTHORIZED","로그인이 필요합니다."); }
    }
    public Map<String,Object> user() { var row=db.one("SELECT id,email,name,phone,role,status,created_at,updated_at FROM users WHERE id=?",id()); if(!"ACTIVE".equals(row.get("status")))throw AppException.forbidden(); return row; }
    public boolean admin() { return "ADMIN".equals(user().get("role")); }
    public void requireAdmin() { if(!admin())throw AppException.forbidden(); }
    public void requireAdmin(long cohortId) { requireAdmin(); db.one("SELECT id FROM cohorts WHERE id=?",cohortId); }
    public Map<String,Object> cohort(long cohortId) {
        db.one("SELECT id FROM cohorts WHERE id=?",cohortId);
        if(admin())return Map.of("id",0L,"cohortId",cohortId,"userId",id(),"role","ADMIN","status","ACTIVE");
        return db.find("SELECT * FROM enrollments WHERE cohort_id=? AND user_id=? AND status='ACTIVE'",cohortId,id()).orElseThrow(AppException::forbidden);
    }
    public void requireOwner(long userId) { if(id()!=userId&&!admin())throw AppException.forbidden(); }
}
