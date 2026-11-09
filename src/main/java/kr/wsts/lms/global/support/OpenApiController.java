package kr.wsts.lms.global.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OpenApiController {
    @GetMapping(value="/api-docs",produces=MediaType.APPLICATION_JSON_VALUE)
    public Resource specification(){return new ClassPathResource("api/openapi.json");}
}
