package io.github.backoutside.rivet.dianping.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /** ACCEPTED, SOLD_OUT, NOT_STARTED, ENDED or NOT_FOUND. */
    private String status;
    /** Populated only for ACCEPTED. */
    private String orderId;
}
