package kr.wsts.lms.domain.notification;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.*;

@RestController @RequestMapping("/api/notifications")
public class NotificationController {
    private final Db db;private final Actor actor;
    public NotificationController(Db db,Actor actor){this.db=db;this.actor=actor;}
    @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="false")boolean unread,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){String where=" FROM notifications WHERE user_id=?"+(unread?" AND read_at IS NULL":"");return db.page("SELECT *"+where+" ORDER BY id DESC","SELECT COUNT(*)"+where,page,size,actor.id());}
    @PatchMapping("/{id}/read") public Map<String,Object> read(@PathVariable long id){if(db.find("SELECT id FROM notifications WHERE id=? AND user_id=?",id,actor.id()).isEmpty())throw AppException.notFound();db.update("UPDATE notifications SET read_at=COALESCE(read_at,?) WHERE id=? AND user_id=?",Db.now(),id,actor.id());return db.one("SELECT * FROM notifications WHERE id=? AND user_id=?",id,actor.id());}
    @PostMapping("/read-all") public Map<String,Object> all(){return Map.of("updated",db.update("UPDATE notifications SET read_at=? WHERE user_id=? AND read_at IS NULL",Db.now(),actor.id()));}
}
