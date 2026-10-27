package com.ticarvo.identity.service;

import java.util.UUID;
import org.springframework.security.core.Authentication;
public interface CurrentUserProvider { UUID currentUserId(Authentication authentication); }
