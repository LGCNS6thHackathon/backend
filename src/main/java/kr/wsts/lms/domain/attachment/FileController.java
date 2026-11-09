package kr.wsts.lms.domain.attachment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class FileController {
    private final FileService files;
    public FileController(FileService files) {this.files=files;}

    @PostMapping(value="/posts/{postId}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> postFile(@PathVariable long postId,@RequestPart("file") MultipartFile file) throws IOException {
        return files.upload("post",postId,file);
    }

    @PostMapping(value="/assignments/{assignmentId}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> assignmentFile(@PathVariable long assignmentId,@RequestPart("file") MultipartFile file) throws IOException {
        return files.upload("assignment",assignmentId,file);
    }

    @GetMapping("/submissions/{submissionId}/files")
    public List<Map<String,Object>> submissionFiles(@PathVariable long submissionId) {
        return files.submissionFiles(submissionId);
    }

    @PostMapping(value="/submissions/{submissionId}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> submissionFile(@PathVariable long submissionId,@RequestPart("file") MultipartFile file) throws IOException {
        return files.upload("submission",submissionId,file);
    }

    @GetMapping("/files/{fileId}")
    public Map<String,Object> get(@PathVariable long fileId) {return files.get(fileId);}

    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<Resource> download(@PathVariable long fileId) throws IOException {
        var file=files.get(fileId);Path path=files.storedPath(fileId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename((String)file.get("originalName"),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").body(new FileSystemResource(path));
    }

    @DeleteMapping("/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long fileId) {files.delete(fileId);}
}
