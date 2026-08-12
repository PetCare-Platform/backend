package com.mycom.petcoupon.experiment.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.experiment.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
}
