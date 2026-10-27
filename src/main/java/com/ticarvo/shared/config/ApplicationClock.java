package com.ticarvo.shared.config;

import org.springframework.context.annotation.*;
import java.time.Clock;
@Configuration
public class ApplicationClock {
    @Bean Clock clock() { return Clock.systemUTC(); }
}
