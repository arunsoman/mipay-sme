package trustplane.comms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CommsApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommsApplication.class, args);
    }
}
