package com.mycom.petcoupon.experiment.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.experiment.entity.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
}
