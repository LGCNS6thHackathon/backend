package kr.wsts.lms.domain.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;
import kr.wsts.lms.global.support.Input;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class AdminUsersController {
    private static final String COLUMNS = "id,email,name,phone,role,status,created_at,updated_at";
    private final Db db;
    private final Actor actor;
    public AdminUsersController(Db db, Actor actor) { this.db = db; this.actor = actor; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        actor.requireAdmin();
        if (q.length() > 100) throw AppException.bad("검색어는 100자 이하여야 합니다.");
        String like = "%" + q.trim() + "%";
        return db.page("SELECT " + COLUMNS + " FROM users WHERE email LIKE ? OR name LIKE ? ORDER BY id DESC",
                "SELECT COUNT(*) FROM users WHERE email LIKE ? OR name LIKE ?", page, size, like, like);
    }

    @PatchMapping("/{userId}")
    @Transactional
    public Map<String, Object> update(@PathVariable long userId, @RequestBody Map<String, Object> input) {
        actor.requireAdmin();
        // Serialize administrator lifecycle updates so two demotions cannot remove the last admin.
        db.list("SELECT id FROM users WHERE role='ADMIN' AND status='ACTIVE' ORDER BY id FOR UPDATE");
        Map<String, Object> current = db.one("SELECT " + COLUMNS + " FROM users WHERE id=? FOR UPDATE", userId);
        Map<String, Object> merged = new LinkedHashMap<>(current); merged.putAll(input);
        String role = Input.choice(merged, "role", "ADMIN", "INSTRUCTOR", "STUDENT");
        String status = Input.choice(merged, "status", "ACTIVE", "DISABLED");
        if ("ADMIN".equals(current.get("role")) && "ACTIVE".equals(current.get("status"))
                && (!"ADMIN".equals(role) || !"ACTIVE".equals(status))
                && db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'") <= 1) {
            throw AppException.conflict("마지막 활성 관리자의 권한은 제거할 수 없습니다.");
        }
        if (!role.equals(current.get("role")) || !status.equals(current.get("status"))) {
            db.update("UPDATE users SET role=?,status=?,token_version=token_version+1,updated_at=? WHERE id=?", role, status, Db.now(), userId);
            db.update("UPDATE refresh_tokens SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", Db.now(), userId);
        }
        return db.one("SELECT " + COLUMNS + " FROM users WHERE id=?", userId);
    }
}
