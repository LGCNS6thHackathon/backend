package kr.wsts.lms.domain.community;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.*;

@RestController @RequestMapping("/api")
public class StudyGroupController {
    private final Db db;private final Actor actor;private final Notices notices;
    public StudyGroupController(Db db,Actor actor,Notices notices){this.db=db;this.actor=actor;this.notices=notices;}
    private Map<String,Object> group(long id,boolean lock){var g=db.one("SELECT * FROM study_groups WHERE id=?"+(lock?" FOR UPDATE":""),id);actor.cohort(Db.id(g,"cohortId"));return g;}
    private void owner(Map<String,Object> g){if(Db.id(g,"ownerId")!=actor.id()){actor.staff(Db.id(g,"cohortId"));}}
    private Map<String,Object> detail(long id){var g=group(id,false);g.put("memberCount",db.count("SELECT COUNT(*) FROM study_group_members WHERE group_id=?",id));g.put("joined",db.count("SELECT COUNT(*) FROM study_group_members WHERE group_id=? AND user_id=?",id,actor.id())>0);return g;}
    @GetMapping("/cohorts/{cohortId}/study-groups")
    public Map<String,Object> groups(@PathVariable long cohortId,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="false")boolean mine,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){actor.cohort(cohortId);if(q.length()>100)throw AppException.bad("검색어는 100자 이하로 입력해주세요.");var args=new ArrayList<Object>();args.add(cohortId);String where=" FROM study_groups g WHERE g.cohort_id=?";if(!q.isBlank()){where+=" AND g.name LIKE ?";args.add("%"+q+"%");}if(mine){where+=" AND EXISTS(SELECT 1 FROM study_group_members m WHERE m.group_id=g.id AND m.user_id=?)";args.add(actor.id());}return db.page("SELECT g.*,(SELECT COUNT(*) FROM study_group_members m WHERE m.group_id=g.id) AS member_count"+where+" ORDER BY g.id DESC","SELECT COUNT(*)"+where,page,size,args.toArray());}
    @PostMapping("/cohorts/{cohortId}/study-groups") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> create(@PathVariable long cohortId,@RequestBody Map<String,Object> input){actor.cohort(cohortId);long id=db.insert("INSERT INTO study_groups(cohort_id,owner_id,name,description,capacity,status,created_at,updated_at) VALUES(?,?,?,?,?,'OPEN',?,?)",cohortId,actor.id(),Input.str(input,"name",2,120),Input.str(input,"description",2,10000),Input.integer(input,"capacity",5,2,30),Db.now(),Db.now());db.insert("INSERT INTO study_group_members(group_id,user_id,joined_at) VALUES(?,?,?)",id,actor.id(),Db.now());return detail(id);}
    @GetMapping("/study-groups/{groupId}") public Map<String,Object> get(@PathVariable long groupId){return detail(groupId);}
    @PatchMapping("/study-groups/{groupId}") @Transactional
    public Map<String,Object> edit(@PathVariable long groupId,@RequestBody Map<String,Object> input){var g=group(groupId,true);owner(g);int capacity=Input.integer(input,"capacity",((Number)g.get("capacity")).intValue(),2,30);if(capacity<db.count("SELECT COUNT(*) FROM study_group_members WHERE group_id=?",groupId))throw AppException.conflict("현재 인원보다 정원을 줄일 수 없습니다.");db.update("UPDATE study_groups SET name=?,description=?,capacity=?,status=?,updated_at=? WHERE id=?",input.containsKey("name")?Input.str(input,"name",2,120):g.get("name"),input.containsKey("description")?Input.str(input,"description",2,10000):g.get("description"),capacity,input.containsKey("status")?Input.choice(input,"status","OPEN","CLOSED"):g.get("status"),Db.now(),groupId);return detail(groupId);}
    @DeleteMapping("/study-groups/{groupId}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void delete(@PathVariable long groupId){var g=group(groupId,true);owner(g);db.update("DELETE FROM study_group_members WHERE group_id=?",groupId);db.update("DELETE FROM study_groups WHERE id=?",groupId);}
    @PostMapping("/study-groups/{groupId}/members/me") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> join(@PathVariable long groupId){var g=group(groupId,true);if(db.count("SELECT COUNT(*) FROM study_group_members WHERE group_id=? AND user_id=?",groupId,actor.id())>0)throw AppException.conflict("이미 가입한 스터디입니다.");if(!"OPEN".equals(g.get("status")))throw AppException.conflict("모집이 종료된 스터디입니다.");if(db.count("SELECT COUNT(*) FROM study_group_members WHERE group_id=?",groupId)>=((Number)g.get("capacity")).longValue())throw AppException.conflict("스터디 정원이 가득 찼습니다.");db.insert("INSERT INTO study_group_members(group_id,user_id,joined_at) VALUES(?,?,?)",groupId,actor.id(),Db.now());if(Db.id(g,"ownerId")!=actor.id())notices.user(Db.id(g,"ownerId"),Db.id(g,"cohortId"),"STUDY_JOIN","새로운 스터디 멤버가 참여했습니다.",(String)g.get("name"),"STUDY_GROUP",groupId);return detail(groupId);}
    @DeleteMapping("/study-groups/{groupId}/members/me") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void leave(@PathVariable long groupId){var g=group(groupId,true);if(Db.id(g,"ownerId")==actor.id())throw AppException.conflict("운영자는 스터디를 삭제하거나 운영을 계속해주세요.");if(db.update("DELETE FROM study_group_members WHERE group_id=? AND user_id=?",groupId,actor.id())==0)throw AppException.notFound();}
    @GetMapping("/study-groups/{groupId}/members") public List<Map<String,Object>> members(@PathVariable long groupId){group(groupId,false);return db.list("SELECT m.id,m.user_id,u.name,m.joined_at FROM study_group_members m JOIN users u ON u.id=m.user_id WHERE m.group_id=? ORDER BY m.id",groupId);}
}
