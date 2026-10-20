package com.qlink.features.user.dto;
import jakarta.validation.constraints.Size;
/** Null/blank removes the optional, unverified discovery number. */
public record PhoneNumberRequest(@Size(max = 64) String phoneNumber) {}
