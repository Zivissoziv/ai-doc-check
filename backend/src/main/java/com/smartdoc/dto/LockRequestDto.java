package com.smartdoc.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LockRequestDto {

    private String password;
}
