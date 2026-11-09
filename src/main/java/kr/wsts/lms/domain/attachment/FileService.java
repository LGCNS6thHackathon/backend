package kr.wsts.lms.domain.attachment;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.*;

/** Shared storage operations also participate in the assignment submission transaction. */
@Service
public class FileService {
    private static final Logger log=LoggerFactory.getLogger(FileService.class);
    private final Db db;
    private final Actor actor;
    private final Path storage;

    public FileService(Db db,Actor actor,@Value("${app.storage.directory}") String storage) {
        this.db=db;this.actor=actor;this.storage=Path.of(storage).toAbsolutePath().normalize();
    }

    private Map<String,Object> parent(String type,long id,boolean write) {
        Map<String,Object> p;
        if(type.equals("post")) {
            p=db.one("SELECT cohort_id,author_id AS owner_id FROM posts WHERE id=? AND deleted_at IS NULL",id);
            actor.cohort(Db.id(p,"cohortId"));
            if(write&&Db.id(p,"ownerId")!=actor.id())actor.staff(Db.id(p,"cohortId"));
        } else if(type.equals("assignment")) {
            p=db.one("SELECT cohort_id,author_id AS owner_id FROM assignments WHERE id=? AND deleted_at IS NULL"+(write?" FOR UPDATE":""),id);
            if(write)actor.staff(Db.id(p,"cohortId"));else actor.cohort(Db.id(p,"cohortId"));
        } else {
            long assignment=Db.id(db.one("SELECT assignment_id FROM submissions WHERE id=?",id),"assignmentId");
            // Use the same parent -> submission lock order as submission editing and grading.
            var a=db.one("SELECT cohort_id,due_at FROM assignments WHERE id=? AND deleted_at IS NULL"+(write?" FOR UPDATE":""),assignment);
            p=new LinkedHashMap<>(db.one("SELECT user_id AS owner_id,id FROM submissions WHERE id=? AND assignment_id=?"+(write?" FOR UPDATE":""),id,assignment));
            p.putAll(a);
            long cohort=Db.id(p,"cohortId");actor.cohort(cohort);
            if(write) {
                actor.requireOwner(Db.id(p,"ownerId"));
                if(!Db.now().isBefore(OffsetDateTime.parse((String)p.get("dueAt")).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()))
                    throw AppException.conflict("제출 기한이 지났습니다.");
                if(db.count("SELECT COUNT(*) FROM submission_reviews WHERE submission_id=?",id)>0)
                    throw AppException.conflict("검토가 완료된 제출물은 수정할 수 없습니다.");
            } else if(Db.id(p,"ownerId")!=actor.id())actor.staff(cohort);
        }
        return p;
    }

    private Map<String,Object> metadata(long id) {
        return withDownloadUrl(db.one("SELECT id,cohort_id,uploader_id,post_id,assignment_id,submission_id,original_name,content_type,size_bytes,created_at FROM files WHERE id=? AND deleted_at IS NULL",id));
    }

    private void access(Map<String,Object> file,boolean write) {
        String type=file.get("postId")!=null?"post":file.get("assignmentId")!=null?"assignment":"submission";
        var p=parent(type,Db.id(file,type+"Id"),write);
        if(Db.id(p,"cohortId")!=Db.id(file,"cohortId"))throw AppException.notFound();
        if(write&&Db.id(file,"uploaderId")!=actor.id())actor.staff(Db.id(file,"cohortId"));
    }

    public List<Map<String,Object>> submissionFiles(long submissionId) {
        parent("submission",submissionId,false);
        return summaries("submission_id",submissionId);
    }

    public List<Map<String,Object>> assignmentFiles(long assignmentId) {
        parent("assignment",assignmentId,false);
        return summaries("assignment_id",assignmentId);
    }

    private List<Map<String,Object>> summaries(String parentColumn,long id) {
        return db.list("SELECT id,original_name,content_type,size_bytes,created_at FROM files WHERE "+parentColumn+"=? AND deleted_at IS NULL ORDER BY created_at DESC,id DESC",id)
                .stream().map(this::withDownloadUrl).toList();
    }

    private Map<String,Object> withDownloadUrl(Map<String,Object> file) {
        file.put("downloadUrl","/api/files/"+Db.id(file,"id")+"/download");
        return file;
    }

    @Transactional(rollbackFor=IOException.class)
    public Map<String,Object> upload(String type,long id,MultipartFile file) throws IOException {
        var p=parent(type,id,true);
        long limit=(type.equals("submission")?50L:10L)*1024*1024;
        if(file.isEmpty()||file.getSize()>limit)
            throw AppException.bad("파일 크기는 1바이트~"+(type.equals("submission")?"50":"10")+"MiB여야 합니다.");
        String original=file.getOriginalFilename()==null?"attachment":file.getOriginalFilename().replace('\\','/');
        original=original.substring(original.lastIndexOf('/')+1).replace("\r","").replace("\n","");
        if(original.isBlank()||original.length()>255)throw AppException.bad("파일 이름을 확인해주세요.");
        String stored=UUID.randomUUID().toString();Path target=storage.resolve(stored);
        String content=file.getContentType()==null?"application/octet-stream":file.getContentType();
        if(content.length()>160)throw AppException.bad("파일 형식을 확인해주세요.");
        Files.createDirectories(storage);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED)try {Files.deleteIfExists(target);}
                catch(IOException failure) {log.error("Failed to remove rolled-back upload {}",target,failure);}
            }
        });
        try {
            try(InputStream in=file.getInputStream()) {Files.copy(in,target);}
            long fileId=db.insert("INSERT INTO files(cohort_id,uploader_id,post_id,assignment_id,submission_id,original_name,stored_name,content_type,size_bytes,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    Db.id(p,"cohortId"),actor.id(),type.equals("post")?id:null,type.equals("assignment")?id:null,type.equals("submission")?id:null,
                    original,stored,content,file.getSize(),Db.now());
            if(type.equals("submission")) {
                var submitted=Db.now();
                db.update("UPDATE submissions SET submitted_at=?,updated_at=? WHERE id=?",submitted,submitted,id);
            }
            return metadata(fileId);
        } catch(IOException|RuntimeException failure) {
            try {Files.deleteIfExists(target);}catch(IOException cleanupFailure) {failure.addSuppressed(cleanupFailure);}
            throw failure;
        }
    }

    public Map<String,Object> get(long id) {
        var file=metadata(id);access(file,false);return file;
    }

    public Path storedPath(long id) {
        get(id);
        String stored=(String)db.one("SELECT stored_name FROM files WHERE id=? AND deleted_at IS NULL",id).get("storedName");
        Path path=storage.resolve(stored).normalize();
        if(!path.startsWith(storage)||!Files.isRegularFile(path))throw AppException.notFound();
        return path;
    }

    @Transactional
    public void delete(long id) {
        var file=metadata(id);access(file,true);
        db.update("UPDATE files SET deleted_at=? WHERE id=?",Db.now(),id);
    }
}
