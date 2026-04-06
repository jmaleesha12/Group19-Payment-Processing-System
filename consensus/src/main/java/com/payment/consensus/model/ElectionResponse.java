package com.payment.consensus.model;

import lombok.*;

/**
 * Response to election vote request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElectionResponse {

    private int term;
    private boolean voteGranted;
    private String voterId;
}
