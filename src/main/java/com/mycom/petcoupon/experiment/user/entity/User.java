package com.mycom.petcoupon.experiment.user.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "app_user")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "login_id", nullable = false, length = 50)
    private String loginId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public User(
            String loginId,
            String name,
            String email,
            String phone,
            String role,
            String status) {
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("loginId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        this.loginId = loginId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.role = role == null || role.isBlank() ? "MEMBER" : role;
        this.status = status == null || status.isBlank() ? "ACTIVE" : status;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
