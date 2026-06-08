package pk.ni.pasir_dudara_tomasz.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import pk.ni.pasir_dudara_tomasz.config.NotificationWebSocketHandler;
import pk.ni.pasir_dudara_tomasz.dto.GroupNotificationDTO;
import pk.ni.pasir_dudara_tomasz.dto.GroupTransactionDTO;
import pk.ni.pasir_dudara_tomasz.model.Debt;
import pk.ni.pasir_dudara_tomasz.model.Group;
import pk.ni.pasir_dudara_tomasz.model.Membership;
import pk.ni.pasir_dudara_tomasz.model.Transaction;
import pk.ni.pasir_dudara_tomasz.model.TransactionType;
import pk.ni.pasir_dudara_tomasz.model.User;
import pk.ni.pasir_dudara_tomasz.repository.DebtRepository;
import pk.ni.pasir_dudara_tomasz.repository.GroupRepository;
import pk.ni.pasir_dudara_tomasz.repository.MembershipRepository;
import pk.ni.pasir_dudara_tomasz.repository.TransactionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class GroupTransactionService {

    private final GroupRepository groupRepository;
    private final MembershipRepository membershipRepository;
    private final DebtRepository debtRepository;
    private final MembershipService membershipService;
    private final TransactionRepository transactionRepository;
    private final NotificationWebSocketHandler notificationWebSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GroupTransactionService(GroupRepository groupRepository, MembershipRepository membershipRepository, DebtRepository debtRepository, MembershipService membershipService, TransactionRepository transactionRepository, NotificationWebSocketHandler notificationWebSocketHandler) {
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.debtRepository = debtRepository;
        this.membershipService = membershipService;
        this.transactionRepository = transactionRepository;
        this.notificationWebSocketHandler = notificationWebSocketHandler;
    }

    public void addGroupTransaction(GroupTransactionDTO transactionDTO, User currentUser) {
        Group group = groupRepository.findById(transactionDTO.getGroupId())
                .orElseThrow(() -> new EntityNotFoundException("Nie znaleziono Grupy"));

        membershipService.assertCurrentUserIsGroupMember(group.getId());

        List<Membership> members = membershipRepository.findByGroupId(group.getId());
        List<Membership> selectedMembers = selectParticipants(transactionDTO, members, currentUser);

        if (selectedMembers.isEmpty()) {
            throw new IllegalStateException("Grupa nie ma członków, nie można dodać transakcji.");
        }

        Double amountPerUser = transactionDTO.getAmount() / selectedMembers.size();
        boolean expense = "EXPENSE".equals(transactionDTO.getType());

        for (Membership member : selectedMembers) {
            User otherUser = member.getUser();
            if (!otherUser.getId().equals(currentUser.getId())) {
                Debt debt = new Debt();
                debt.setDebtor(expense ? otherUser : currentUser);
                debt.setCreditor(expense ? currentUser : otherUser);
                debt.setGroup(group);
                debt.setAmount(amountPerUser);
                debt.setTitle(transactionDTO.getTitle());
                debtRepository.save(debt);

                String notificationMessage = String.format(Locale.US, "%s dodał wydatek \"%s\" w grupie %s. Twoja część: %.2f zł.",
                        currentUser.getEmail(), transactionDTO.getTitle(), group.getName(), amountPerUser);

                GroupNotificationDTO notification = new GroupNotificationDTO(
                        "GROUP_EXPENSE_ADDED",
                        group.getId(),
                        group.getName(),
                        transactionDTO.getTitle(),
                        transactionDTO.getAmount(),
                        amountPerUser,
                        currentUser.getEmail(),
                        notificationMessage
                );

                try {
                    String json = objectMapper.writeValueAsString(notification);
                    notificationWebSocketHandler.sendNotification(otherUser.getId(), json);
                } catch (Exception e) {
                    System.err.println("Błąd generowania powiadomienia: " + e.getMessage());
                }
            }
        }

        Transaction userTransaction = new Transaction();
        userTransaction.setAmount(transactionDTO.getAmount());
        userTransaction.setType(TransactionType.valueOf(transactionDTO.getType()));
        userTransaction.setUser(currentUser);
        userTransaction.setTimestamp(LocalDateTime.now());
        userTransaction.setNotes("Transakcja grupowa: " + group.getName() + " - " + transactionDTO.getTitle());
        transactionRepository.save(userTransaction);
    }

    private List<Membership> selectParticipants(GroupTransactionDTO transactionDTO, List<Membership> members, User currentUser) {
        List<Long> selectedUserIds = transactionDTO.getSelectedUserIds();
        if (selectedUserIds == null || selectedUserIds.isEmpty()) {
            return members;
        }

        java.util.Set<Long> uniqueSelectedUserIds = new java.util.HashSet<>(selectedUserIds);
        List<Membership> selectedMembers = members.stream()
                .filter(membership -> uniqueSelectedUserIds.contains(membership.getUser().getId()))
                .toList();

        if (selectedMembers.size() != uniqueSelectedUserIds.size()) {
            throw new IllegalStateException("Wszyscy wybrani uzytkownicy musza byc członkami grupy.");
        }

        boolean currentUserSelected = selectedMembers.stream()
                .anyMatch(membership -> membership.getUser().getId().equals(currentUser.getId()));

        if (!currentUserSelected) {
            throw new IllegalStateException("Aktualny uzytkownik musi byc uczestnikiem transakcji grupowej.");
        }

        if (selectedMembers.size() < 2) {
            throw new IllegalStateException("Transakcja grupowa wymaga co najmniej dwoch uczestnikow.");
        }

        return selectedMembers;
    }
}