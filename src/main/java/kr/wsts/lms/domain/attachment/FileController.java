package kr.wsts.lms.domain.attachment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.support.*;

@RestController @RequestMapping("/api")
public class FileController {
    private static final Logger log=LoggerFactory.getLogger(FileController.class);
    private final Db db;private final Actor actor;private final Path storage;
    public FileController(Db db,Actor actor,@Value("${app.storage.directory}")String storage){this.db=db;this.actor=actor;this.storage=Path.of(storage).toAbsolutePath().normalize();}
    private Map<String,Object> parent(String type,long id,boolean write){
        Map<String,Object> p;
        if(type.equals("post")){p=db.one("SELECT cohort_id,author_id AS owner_id FROM posts WHERE id=? AND deleted_at IS NULL",id);actor.cohort(Db.id(p,"cohortId"));if(write&&Db.id(p,"ownerId")!=actor.id())actor.staff(Db.id(p,"cohortId"));}
        else if(type.equals("assignment")){p=db.one("SELECT cohort_id,author_id AS owner_id FROM assignments WHERE id=? AND deleted_at IS NULL"+(write?" FOR UPDATE":""),id);if(write)actor.staff(Db.id(p,"cohortId"));else actor.cohort(Db.id(p,"cohortId"));}
        else {
            long assignment=Db.id(db.one("SELECT assignment_id FROM submissions WHERE id=?",id),"assignmentId");
            // Match assignment submission/review writes: lock the parent before its submission.
            var a=db.one("SELECT cohort_id,due_at FROM assignments WHERE id=? AND deleted_at IS NULL"+(write?" FOR UPDATE":""),assignment);
            p=new LinkedHashMap<>(db.one("SELECT user_id AS owner_id,id FROM submissions WHERE id=? AND assignment_id=?"+(write?" FOR UPDATE":""),id,assignment));p.putAll(a);
            long cohort=Db.id(p,"cohortId");actor.cohort(cohort);
            if(write){actor.requireOwner(Db.id(p,"ownerId"));if(!Instant.now().isBefore(OffsetDateTime.parse((String)p.get("dueAt")).toInstant()))throw AppException.conflict("제출 기한이 지났습니다.");if(db.count("SELECT COUNT(*) FROM submission_reviews WHERE submission_id=?",id)>0)throw AppException.conflict("검토가 완료된 제출물은 수정할 수 없습니다.");}
            else if(Db.id(p,"ownerId")!=actor.id())actor.staff(cohort);
        }
        return p;
    }
    private Map<String,Object> metadata(long id){return db.one("SELECT id,cohort_id,uploader_id,post_id,assignment_id,submission_id,original_name,content_type,size_bytes,created_at FROM files WHERE id=? AND deleted_at IS NULL",id);}
    private void access(Map<String,Object> f,boolean write){String type=f.get("postId")!=null?"post":f.get("assignmentId")!=null?"assignment":"submission";long id=Db.id(f,type+"Id");var p=parent(type,id,write);if(Db.id(p,"cohortId")!=Db.id(f,"cohortId"))throw AppException.notFound();if(write&&Db.id(f,"uploaderId")!=actor.id())actor.staff(Db.id(f,"cohortId"));}
    private Map<String,Object> upload(String type,long id,MultipartFile file)throws IOException {
        var p=parent(type,id,true);if(file.isEmpty()||file.getSize()>10*1024*1024)throw AppException.bad("파일 크기는 1바이트~10MB여야 합니다.");
        String original=file.getOriginalFilename()==null?"attachment":file.getOriginalFilename().replace('\\','/');original=original.substring(original.lastIndexOf('/')+1).replace("\r","").replace("\n","");if(original.isBlank()||original.length()>255)throw AppException.bad("파일 이름을 확인해주세요.");
        String stored=UUID.randomUUID().toString();Path target=storage.resolve(stored);String content=file.getContentType()==null?"application/octet-stream":file.getContentType();if(content.length()>160)throw AppException.bad("파일 형식을 확인해주세요.");
        Files.createDirectories(storage);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException failure){log.error("Failed to remove rolled-back upload {}",target,failure);}}
        });
        try {
            try(InputStream in=file.getInputStream()){Files.copy(in,target);}
            long fileId=db.insert("INSERT INTO files(cohort_id,uploader_id,post_id,assignment_id,submission_id,original_name,stored_name,content_type,size_bytes,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",Db.id(p,"cohortId"),actor.id(),type.equals("post")?id:null,type.equals("assignment")?id:null,type.equals("submission")?id:null,original,stored,content,file.getSize(),Db.now());
            if(type.equals("submission")){var submitted=Db.now();db.update("UPDATE submissions SET submitted_at=?,updated_at=? WHERE id=?",submitted,submitted,id);}
            return metadata(fileId);
        }catch(IOException|RuntimeException failure){try{Files.deleteIfExists(target);}catch(IOException cleanupFailure){failure.addSuppressed(cleanupFailure);}throw failure;}
    }
    @PostMapping(value="/posts/{postId}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) @Transactional(rollbackFor=IOException.class)
    public Map<String,Object> postFile(@PathVariable long postId,@RequestPart("file")MultipartFile file)throws IOException{return upload("post",postId,file);}
    @PostMapping(value="/assignments/{assignmentId}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) @Transactional(rollbackFor=IOException.class)
    public Map<String,Object> assignmentFile(@PathVariable long assignmentId,@RequestPart("file")MultipartFile file)throws IOException{return upload("assignment",assignmentId,file);}
    @PostMapping(value="/submissions/{submissionId}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) @Transactional(rollbackFor=IOException.class)
    public Map<String,Object> submissionFile(@PathVariable long submissionId,@RequestPart("file")MultipartFile file)throws IOException{return upload("submission",submissionId,file);}
    @GetMapping("/files/{fileId}") public Map<String,Object> get(@PathVariable long fileId){var f=metadata(fileId);access(f,false);return f;}
    @GetMapping("/files/{fileId}/download") public ResponseEntity<Resource> download(@PathVariable long fileId)throws IOException {var f=metadata(fileId);access(f,false);String stored=(String)db.one("SELECT stored_name FROM files WHERE id=?",fileId).get("storedName");Path path=storage.resolve(stored).normalize();if(!path.startsWith(storage)||!Files.isRegularFile(path))throw AppException.notFound();return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(Files.size(path)).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename((String)f.get("originalName"),StandardCharsets.UTF_8).build().toString()).header("X-Content-Type-Options","nosniff").body(new FileSystemResource(path));}
    @DeleteMapping("/files/{fileId}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void delete(@PathVariable long fileId){var f=metadata(fileId);access(f,true);db.update("UPDATE files SET deleted_at=? WHERE id=?",Db.now(),fileId);}
}
