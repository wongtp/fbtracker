package com.fbtracker.backend;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;


public interface OAuthTokenRepository extends JpaRepository<OAuthToken, UUID> {
}
    
