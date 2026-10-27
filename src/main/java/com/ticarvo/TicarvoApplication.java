package com.ticarvo;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.Arrays;
import java.util.stream.Stream;

@SpringBootApplication
public class TicarvoApplication {
    public static void main(String[] args) {
        // Admin grant/revoke (ADR-008) runs once, then exits. port -1 keeps the web context (Spring Session needs it)
        // without listening; a command-line argument outranks the PORT setting.
        boolean adminCommand = Arrays.stream(args).anyMatch(arg -> arg.startsWith("--ticarvo.admin."));
        if (!adminCommand) { SpringApplication.run(TicarvoApplication.class, args); return; }
        var context = SpringApplication.run(TicarvoApplication.class, Stream.concat(Arrays.stream(args), Stream.of("--server.port=-1")).toArray(String[]::new));
        System.exit(SpringApplication.exit(context));
    }
}
