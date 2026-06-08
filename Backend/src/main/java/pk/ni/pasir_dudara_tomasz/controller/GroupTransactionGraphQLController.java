package pk.ni.pasir_dudara_tomasz.controller;

import jakarta.validation.Valid;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;
import pk.ni.pasir_dudara_tomasz.dto.GroupTransactionDTO;
import pk.ni.pasir_dudara_tomasz.model.User;
import pk.ni.pasir_dudara_tomasz.service.CurrentUserService;
import pk.ni.pasir_dudara_tomasz.service.GroupTransactionService;

@Controller
public class GroupTransactionGraphQLController {
    private final GroupTransactionService groupTransactionService;
    private final CurrentUserService currentUserService;

    public GroupTransactionGraphQLController(GroupTransactionService groupTransactionService, CurrentUserService currentUserService) {
        this.groupTransactionService = groupTransactionService;
        this.currentUserService = currentUserService;
    }

    @MutationMapping
    public Boolean addGroupTransaction(@Valid @Argument GroupTransactionDTO groupTransactionDTO) {
        User user = currentUserService.getCurrentUser();
        groupTransactionService.addGroupTransaction(groupTransactionDTO, user);
        return true;
    }
}