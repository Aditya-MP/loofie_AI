package com.loofie.core.notification.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.notification.app.NotificationService;
import com.loofie.core.notification.app.NotificationService.Item;

/** The caller's own inbox. Page with {@code before} = the last item's {@code createdAt}. */
@RestController
@RequestMapping("/api/core/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    List<Item> inbox(Actor actor, @RequestParam(defaultValue = "false") boolean unreadOnly,
                     @RequestParam(required = false) Instant before, @RequestParam(defaultValue = "50") int limit) {
        return notifications.inbox(actor, unreadOnly, before, limit);
    }

    /** 204 when marked; 404 when it is not in the caller's inbox (indistinguishable from missing). */
    @PostMapping("/{id}/read")
    ResponseEntity<Void> read(Actor actor, @PathVariable UUID id) {
        return notifications.markRead(actor, id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
