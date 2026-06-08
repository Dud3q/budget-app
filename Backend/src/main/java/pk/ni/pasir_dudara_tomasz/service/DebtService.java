package pk.ni.pasir_dudara_tomasz.service;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import pk.ni.pasir_dudara_tomasz.dto.DebtDTO;
import pk.ni.pasir_dudara_tomasz.model.Debt;
import pk.ni.pasir_dudara_tomasz.model.Group;
import pk.ni.pasir_dudara_tomasz.model.Transaction;
import pk.ni.pasir_dudara_tomasz.model.TransactionType;
import pk.ni.pasir_dudara_tomasz.model.User;
import pk.ni.pasir_dudara_tomasz.repository.DebtRepository;
import pk.ni.pasir_dudara_tomasz.repository.GroupRepository;
import pk.ni.pasir_dudara_tomasz.repository.TransactionRepository;
import pk.ni.pasir_dudara_tomasz.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DebtService {
    private final DebtRepository debtRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final MembershipService membershipService;
    private final CurrentUserService currentUserService;
    private final TransactionRepository transactionRepository;

    public DebtService(DebtRepository debtRepository, GroupRepository groupRepository, UserRepository userRepository, MembershipService membershipService, CurrentUserService currentUserService, TransactionRepository transactionRepository) {
        this.debtRepository = debtRepository;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.membershipService = membershipService;
        this.currentUserService = currentUserService;
        this.transactionRepository = transactionRepository;
    }

    public List<Debt> getGroupDebts(Long groupId) {
        membershipService.assertCurrentUserIsGroupMember(groupId);
        return debtRepository.findByGroupId(groupId);
    }

    public Debt createDebt(DebtDTO debtDTO) {
        Group group = groupRepository.findById(debtDTO.getGroupId())
                .orElseThrow(() -> new EntityNotFoundException("Nie można utworzyć długu. Grupa o ID " + debtDTO.getGroupId() + " nie istnieje."));

        User debtor = userRepository.findById(debtDTO.getDebtorId())
                .orElseThrow(() -> new EntityNotFoundException("Nie można utworzyć długu. Dłużnik o ID " + debtDTO.getDebtorId() + " nie istnieje."));

        User creditor = userRepository.findById(debtDTO.getCreditorId())
                .orElseThrow(() -> new EntityNotFoundException("Nie można utworzyć długu. Wierzyciel o ID " + debtDTO.getCreditorId() + " nie istnieje."));

        membershipService.assertCurrentUserIsGroupMember(group.getId());
        membershipService.assertUserIsGroupMember(group.getId(), debtor.getId());
        membershipService.assertUserIsGroupMember(group.getId(), creditor.getId());

        if (debtor.getId().equals(creditor.getId())) {
            throw new IllegalStateException("Dłużnik i wierzyciel muszą być różnymi użytkownikami.");
        }

        User currentUser = currentUserService.getCurrentUser();
        assertCurrentUserCanManageDebt(group, debtor, creditor, currentUser);

        Debt debt = new Debt();
        debt.setGroup(group);
        debt.setDebtor(debtor);
        debt.setCreditor(creditor);
        debt.setAmount(debtDTO.getAmount());
        debt.setTitle(debtDTO.getTitle());

        return debtRepository.save(debt);
    }

    private void assertCurrentUserCanManageDebt(Group group, User debtor, User creditor, User currentUser) {
        boolean isGroupOwner = group.getOwner().getId().equals(currentUser.getId());
        boolean isDebtParticipant = debtor.getId().equals(currentUser.getId()) || creditor.getId().equals(currentUser.getId());

        if (!isGroupOwner && !isDebtParticipant) {
            throw new AccessDeniedException("Tylko właściciel grupy albo uczestnik długu może wykonać te operacje.");
        }
    }

    public void deleteDebt(Long debtId) {
        Debt debt = debtRepository.findById(debtId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Nie można usunąć długu. Dług o ID " + debtId + " nie istnieje."));

        membershipService.assertCurrentUserIsGroupMember(debt.getGroup().getId());
        User currentUser = currentUserService.getCurrentUser();

        assertCurrentUserCanManageDebt(debt.getGroup(), debt.getDebtor(), debt.getCreditor(), currentUser);

        debtRepository.delete(debt);
    }

    public Debt markDebtAsPaid(Long debtId) {
        Debt debt = getDebtForCurrentGroupMember(debtId);
        User currentUser = currentUserService.getCurrentUser();

        if (!debt.getDebtor().getId().equals(currentUser.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Tylko dluznik moze oznaczyc dlug jako oplacony.");
        }

        debt.setPaidByDebtor(true);
        debt.setConfirmedByCreditor(false);
        return debtRepository.save(debt);
    }

    public Debt confirmDebtPayment(Long debtId) {
        Debt debt = getDebtForCurrentGroupMember(debtId);
        User currentUser = currentUserService.getCurrentUser();

        if (!debt.getCreditor().getId().equals(currentUser.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Tylko wierzyciel moze potwierdzic splate dlugu.");
        }

        if (!debt.isPaidByDebtor()) {
            throw new IllegalStateException("Dlug musi zostać najpierw oznaczony jako oplacony przez dluznika.");
        }

        debt.setConfirmedByCreditor(true);
        Debt savedDebt = debtRepository.save(debt);

        Transaction incomeTransaction = new Transaction();
        incomeTransaction.setAmount(debt.getAmount());
        incomeTransaction.setType(TransactionType.INCOME);
        incomeTransaction.setUser(debt.getCreditor());
        incomeTransaction.setTimestamp(LocalDateTime.now());
        incomeTransaction.setNotes("Spłata długu od: " + debt.getDebtor().getEmail() + " za: " + debt.getTitle());
        transactionRepository.save(incomeTransaction);

        Transaction expenseTransaction = new Transaction();
        expenseTransaction.setAmount(debt.getAmount());
        expenseTransaction.setType(TransactionType.EXPENSE);
        expenseTransaction.setUser(debt.getDebtor());
        expenseTransaction.setTimestamp(LocalDateTime.now());
        expenseTransaction.setNotes("Spłata długu dla: " + debt.getCreditor().getEmail() + " za: " + debt.getTitle());
        transactionRepository.save(expenseTransaction);

        return savedDebt;
    }

    private Debt getDebtForCurrentGroupMember(Long debtId) {
        Debt debt = debtRepository.findById(debtId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Nie znaleziono dlugu o ID " + debtId + "."));
        membershipService.assertCurrentUserIsGroupMember(debt.getGroup().getId());
        return debt;
    }
}