package com.mycom.petcoupon.experiment.coupon.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.experiment.coupon.type.CouponIssueStrategy;

@Component
public class CouponIssueServiceResolver {

    private final Map<CouponIssueStrategy, CouponIssueService> services;

    public CouponIssueServiceResolver(List<CouponIssueService> serviceList) {
        EnumMap<CouponIssueStrategy, CouponIssueService> servicesByStrategy =
                new EnumMap<>(CouponIssueStrategy.class);

        for (CouponIssueService service : serviceList) {
            CouponIssueStrategy strategy = service.supports();
            CouponIssueService existingService = servicesByStrategy.putIfAbsent(strategy, service);

            if (existingService != null) {
                throw new IllegalStateException(
                        "Duplicate coupon issue strategy: " + strategy);
            }
        }

        this.services = Map.copyOf(servicesByStrategy);
    }

    public CouponIssueService resolve(CouponIssueStrategy strategy) {
        CouponIssueService service = services.get(strategy);

        if (service == null) {
            throw new IllegalArgumentException(
                    "Unsupported coupon issue strategy: " + strategy);
        }

        return service;
    }
}
