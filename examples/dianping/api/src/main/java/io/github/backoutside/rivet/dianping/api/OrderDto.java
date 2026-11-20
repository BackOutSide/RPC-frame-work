package io.github.backoutside.rivet.dianping.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String orderId;
    private long voucherId;
    /** PENDING or CONFIRMED. */
    private String status;
}
