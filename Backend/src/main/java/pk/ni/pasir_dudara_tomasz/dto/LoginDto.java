package pk.ni.pasir_dudara_tomasz.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginDto {

    @NotBlank
    private String email;

    @NotBlank
    private String password;
}