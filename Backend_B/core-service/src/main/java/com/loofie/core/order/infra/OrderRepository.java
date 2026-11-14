package com.loofie.core.order.infra;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.loofie.core.order.domain.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {
}
