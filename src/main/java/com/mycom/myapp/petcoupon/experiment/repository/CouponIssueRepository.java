package com.mycom.petcoupon.experiment.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.experiment.entity.CouponIssue;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    long countByCouponId(Long couponId);

    long deleteByCouponId(Long couponId);
}
