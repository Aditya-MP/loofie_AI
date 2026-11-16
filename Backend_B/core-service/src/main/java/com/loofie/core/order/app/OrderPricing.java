package com.loofie.core.order.app;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.order.domain.Order;
import com.loofie.core.order.infra.OrderRepository;

/** The price terms an order was paid on, for features that work out money (refunds). */
@Service
public class OrderPricing {

    /** Basis points: 1500 = 15%. */
    public record Terms(int loofieFeeBp, int gstBp) {
    }

    private final OrderRepository orders;

    public OrderPricing(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public Terms terms(UUID orderId) {
        Order o = orders.findById(orderId).orElseThrow(() -> new JobNotFoundException(orderId));
        return new Terms(o.getLoofieFeeBp(), o.getGstBp());
    }
}
