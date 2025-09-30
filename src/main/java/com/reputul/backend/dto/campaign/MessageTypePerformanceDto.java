package com.reputul.backend.dto.campaign;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MessageTypePerformanceDto {
    private Long totalSent;
    private Long delivered;
    private Long opened;
    private Long clicked;
    private Double deliveryRate;
    private Double openRate;
    private Double clickRate;
}
