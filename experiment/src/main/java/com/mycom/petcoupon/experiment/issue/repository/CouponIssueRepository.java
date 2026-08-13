package com.mycom.petcoupon.experiment.issue.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	boolean existsByRequestId(String requestId);

	boolean existsByCoupon_IdAndUser_Id(Long couponId, Long userId);

	long countByCoupon_Id(Long couponId);

	long deleteByCoupon_Id(Long couponId);

	// 요청 ID로 발급 내역 조회
//	Optional<CouponIssue> findByRequestId(String requestId);

	// 쿠폰 발급 내역 조회
	List<CouponIssue> findAllByCoupon_IdOrderByIdAsc(Long couponId);
}
