package com.mycom.petcoupon.experiment.coupon.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.experiment.coupon.entity.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
}
