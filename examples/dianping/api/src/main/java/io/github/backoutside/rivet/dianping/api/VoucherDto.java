package io.github.backoutside.rivet.dianping.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private long shopId;
    private String title;
    private long faceValueCents;
    private long startsAtEpochMillis;
    private long endsAtEpochMillis;
}
