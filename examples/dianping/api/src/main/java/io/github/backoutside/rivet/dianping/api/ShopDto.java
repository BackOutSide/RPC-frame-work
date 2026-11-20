package io.github.backoutside.rivet.dianping.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShopDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private String name;
    private String address;
    private String description;
}
