package kr.wsts.lms.global.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class DatabaseProfileGuard implements ApplicationRunner {
    private final Environment environment;
    public DatabaseProfileGuard(Environment environment){this.environment=environment;}
    @Override public void run(ApplicationArguments args){
        if(!environment.acceptsProfiles(Profiles.of("local","test","mariadb")))throw new IllegalStateException("Use the mariadb profile for deployment, local for development, or test for tests.");
    }
}
