package com.example.sil.shared.orders;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceOrderRepository extends JpaRepository<ServiceOrder, String> {

    List<ServiceOrder> findByStateOrderByCreatedAtDesc(ServiceOrderState state);
}
