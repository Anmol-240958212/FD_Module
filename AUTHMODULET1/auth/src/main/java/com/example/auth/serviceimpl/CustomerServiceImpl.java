package com.example.auth.serviceimpl;

import com.example.auth.dto.CustomerProfileRequestDto;
import com.example.auth.dto.CustomerProfileResponseDto;
import com.example.auth.dto.KycCustomerEventDto;
import com.example.auth.entity.*;
import com.example.auth.event.KycApprovedEvent;
import com.example.auth.repository.*;
import com.example.auth.service.CustomerService;
import com.example.auth.service.KycEventPublisherService;
import com.example.auth.util.AuditData;
import com.example.auth.util.AuditUtil;
import com.example.auth.util.MaskingUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

@Service
public class CustomerServiceImpl implements CustomerService {

    @Autowired
    private AuthUsrRepository authUsrRepository;

    @Autowired
    private CustDtlRepository custDtlRepository;

    @Autowired
    private CustStsMstrRepository custStsMstrRepository;

    @Autowired
    private CustIdfnRepository custIdfnRepository;

    @Autowired
    private CustAddrCmpntRepository custAddrCmpntRepository;

    @Autowired
    private CustCntctDtlsRepository custCntctDtlsRepository;

    @Autowired
    private CustNmeCmpntRepository custNmeCmpntRepository;

    @Autowired
    private IdfnTypMstrRepository idfnTypMstrRepository;

    @Autowired
    private AddrTypMstrRepository addrTypMstrRepository;

    @Autowired
    private CntctTypMstrRepository cntctTypMstrRepository;

    @Autowired
    private NmeTypMstrRepository nmeTypMstrRepository;

    @Autowired
    private CustClRepository custClRepository;

    @Autowired
    private CustAcctRepository custAcctRepository;

    @Autowired
    private AuditUtil auditUtil;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public CustomerProfileResponseDto submitKycProfile(CustomerProfileRequestDto request, String username) {
        validateIndianMobileNumber(request.getMobileNumber());
        
        AuthUsr authUsr = authUsrRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        CustDtl customer = authUsr.getCustomer();
        if (customer == null) {
            throw new IllegalStateException("No customer associated with this user");
        }

        validateMobileIsUnique(request.getMobileNumber(), username, customer);

        String currentStatusCode = customer.getCustomerStatus() != null ? customer.getCustomerStatus().getStatusCode() : "KYC_PENDING";
        if ("LOCKED".equals(currentStatusCode) || "CLOSED".equals(currentStatusCode)) {
            throw new IllegalStateException("Cannot update KYC profile for a locked or closed account");
        }

        // 1. Update CustDtl
        customer.setDateOfBirth(request.getDateOfBirth());
        customer.setGender(request.getGender());
        // These are mandatory KYC attributes. Keep safe defaults for older
        // clients that do not yet send the newly added fields.
        customer.setPreferredLanguage(hasText(request.getPreferredLanguage())
                ? request.getPreferredLanguage().trim() : "English");
        customer.setCountryOfOrigin(hasText(request.getCountryOfOrigin())
                ? request.getCountryOfOrigin().trim() : "India");
        
        if (request.getDateOfBirth() != null) {
            int age = Period.between(request.getDateOfBirth(), LocalDate.now()).getYears();
            String classificationValue;
            if (age < 18) {
                classificationValue = "MINOR";
            } else if (age < 60) {
                classificationValue = "ADULT";
            } else {
                classificationValue = "SENIOR_CITIZEN";
            }
            
            CustCl custCl = custClRepository.findByClassificationTypeAndClassificationTypeValue("AGE", classificationValue)
                    .orElseThrow(() -> new IllegalStateException("Classification record not found for " + classificationValue));
            customer.setClassification(custCl);
        }

        String fullName = buildFullName(request.getFirstName(), request.getMiddleName(), request.getLastName());
        customer.setFullName(fullName);
        
        AuditData audit = auditUtil.createAudit("CUSTOMER_KYC");

        customer.setEffectiveDate(audit.getEffectiveDate());
        customer.setCrudValue("U");
        customer.setUserId(audit.getUserId());
        customer.setWorkstationId(audit.getWorkstationId());
        customer.setProgramId(audit.getProgramId());
        customer.setHostTimestamp(audit.getHostTimestamp());
        customer.setLocalTimestamp(audit.getLocalTimestamp());
        customer.setAcceptanceTimestamp(audit.getAcceptanceTimestamp());
        customer.setAcceptanceTimestampUtcOffset(audit.getAcceptanceTimestampUtcOffset());
        customer.setUuid(audit.getUuid());

        // 2. Save Name Components
        saveNameComponent(customer, "FIRST_NAME", request.getFirstName(), audit);
        if (request.getMiddleName() != null && !request.getMiddleName().isBlank()) {
            saveNameComponent(customer, "MIDDLE_NAME", request.getMiddleName(), audit);
        }
        saveNameComponent(customer, "LAST_NAME", request.getLastName(), audit);

        // 3. Save Identification
        if (request.getIdentificationType() != null && request.getIdentificationNumber() != null) {
            IdfnTypMstr idfnType = idfnTypMstrRepository.findByIdentificationType(request.getIdentificationType())
                    .orElseThrow(() -> new IllegalStateException("Invalid identification type: " + request.getIdentificationType()));
            
            CustIdfn custIdfn = custIdfnRepository.findByCustomer(customer).stream()
                    .findFirst()
                    .orElseGet(() -> {
                        CustIdfn idfn = new CustIdfn();
                        idfn.setCustomer(customer);
                        return idfn;
                    });
            
            custIdfn.setIdentificationType(idfnType);
            custIdfn.setIdentificationItem(request.getIdentificationNumber());
            
            custIdfn.setEffectiveDate(audit.getEffectiveDate());
            custIdfn.setCrudValue(custIdfn.getRecordId() == null ? "C" : "U");
            custIdfn.setUserId(audit.getUserId());
            custIdfn.setWorkstationId(audit.getWorkstationId());
            custIdfn.setProgramId(audit.getProgramId());
            custIdfn.setHostTimestamp(audit.getHostTimestamp());
            custIdfn.setLocalTimestamp(audit.getLocalTimestamp());
            custIdfn.setAcceptanceTimestamp(audit.getAcceptanceTimestamp());
            custIdfn.setAcceptanceTimestampUtcOffset(audit.getAcceptanceTimestampUtcOffset());
            custIdfn.setUuid(audit.getUuid());
            
            custIdfnRepository.save(custIdfn);
        }

        // 4. Save Address Components
        saveAddressComponent(customer, "ADDRESS_LINE_1", request.getAddressLine1(), audit);
        if (request.getAddressLine2() != null && !request.getAddressLine2().isBlank()) {
            saveAddressComponent(customer, "ADDRESS_LINE_2", request.getAddressLine2(), audit);
        }
        saveAddressComponent(customer, "CITY", request.getCity(), audit);
        saveAddressComponent(customer, "STATE", request.getState(), audit);
        saveAddressComponent(customer, "COUNTRY", request.getCountry(), audit);
        saveAddressComponent(customer, "POSTAL_CODE", request.getPostalCode(), audit);

        // 5. Save Contact Components
        if (request.getMobileNumber() != null && !request.getMobileNumber().isBlank()) {
            saveContactComponent(customer, "MOBILE", request.getMobileNumber(), audit);
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            saveContactComponent(customer, "EMAIL", request.getEmail(), audit);
        }

        // 6. Transition Status to ACTIVE
        CustStsMstr activeStatus = custStsMstrRepository.findByStatusCode("ACTIVE")
                .orElseThrow(() -> new IllegalStateException("ACTIVE customer status not found"));
        customer.setCustomerStatus(activeStatus);

        custDtlRepository.save(customer);

        // 7. Raise an application event so the Kafka publish happens AFTER the
        // database transaction commits. A failed/rolled-back KYC submission
        // never reaches this point, so no event is published for it.
        eventPublisher.publishEvent(new KycApprovedEvent(customer));

        // 8. Return Response
        CustomerProfileResponseDto response = new CustomerProfileResponseDto();
        response.setMessage("Customer KYC completed successfully");
        response.setStatus("ACTIVE");
        response.setCustomerId(customer.getCustomerIdentifier());
        response.setFullName(customer.getFullName());

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerProfileResponseDto getCustomerProfile(String username) {
        AuthUsr authUsr = authUsrRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        CustDtl customer = authUsr.getCustomer();
        if (customer == null) {
            throw new IllegalStateException("No customer associated with this user");
        }

        CustomerProfileResponseDto response = new CustomerProfileResponseDto();
        response.setMessage("Customer profile fetched successfully");
        if (customer.getCustomerStatus() != null) {
            response.setStatus(customer.getCustomerStatus().getStatusCode());
        }
        response.setCustomerId(customer.getCustomerIdentifier());
        response.setFullName(customer.getFullName());

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public String getCustomerStatus(String username) {
        AuthUsr authUsr = authUsrRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        CustDtl customer = authUsr.getCustomer();
        if (customer == null) {
            throw new IllegalStateException("No customer associated with this user");
        }

        if (customer.getCustomerStatus() == null) {
            throw new IllegalStateException("Customer status is not set");
        }
        
        return customer.getCustomerStatus().getStatusCode();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<CustomerProfileResponseDto> getAllCustomers(String username) {
        AuthUsr authUsr = authUsrRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        // If not STAFF or ADMIN (depending on existing roles), deny access. 
        // We will assume "CUSTOMER" is standard user role.
        if (authUsr.getRole() != null && "CUSTOMER".equals(authUsr.getRole().getRoleCode())) {
            throw new org.springframework.security.access.AccessDeniedException("Access Denied: You do not have permission to access all customers");
        }

        // Read from authenticated customer accounts so newly verified and
        // KYC-pending registrations are included, while staff/admin records
        // are excluded from the customer repository.
        return authUsrRepository.findAll().stream()
                .filter(user -> user.getRole() != null
                        && "CUSTOMER".equals(user.getRole().getRoleCode()))
                .filter(user -> user.getCustomer() != null)
                .map(user -> {
            CustDtl customer = user.getCustomer();
            CustomerProfileResponseDto dto = new CustomerProfileResponseDto();
            dto.setMessage("Customer record");
            dto.setCustomerId(customer.getCustomerIdentifier());
            dto.setFullName(customer.getFullName());
            dto.setGender(customer.getGender());
            if (customer.getCustomerType() != null) {
                dto.setCustomerType(customer.getCustomerType().getTypeCode());
            }
            if (customer.getCustomerStatus() != null) {
                dto.setStatus(customer.getCustomerStatus().getStatusCode());
            }
            if (customer.getClassification() != null) {
                dto.setClassificationType(customer.getClassification().getClassificationType());
                dto.setClassificationValue(customer.getClassification().getClassificationTypeValue());
            }

            dto.setMaskedEmail(MaskingUtil.maskEmail(user.getEmail()));
            dto.setMaskedMobileNumber(MaskingUtil.maskMobileNumber(user.getPhoneNumber()));

            custIdfnRepository.findByCustomer(customer).stream().findFirst().ifPresent(idfn -> {
                if (idfn.getIdentificationType() != null) {
                    dto.setIdentificationType(idfn.getIdentificationType().getIdentificationType());
                }
                dto.setMaskedIdentificationNumber(
                        MaskingUtil.maskIdentificationNumber(idfn.getIdentificationItem()));
            });

            custAddrCmpntRepository.findByCustomer(customer).forEach(address -> {
                if (address.getAddressType() == null) return;
                switch (address.getAddressType().getAddressType()) {
                    case "ADDRESS_LINE_1" -> dto.setAddressLine1(address.getAddressTypeValue());
                    case "CITY" -> dto.setCity(address.getAddressTypeValue());
                    case "STATE" -> dto.setState(address.getAddressTypeValue());
                    case "COUNTRY" -> dto.setCountry(address.getAddressTypeValue());
                    case "POSTAL_CODE" -> dto.setPostalCode(address.getAddressTypeValue());
                    default -> { }
                }
            });
            return dto;
        }).collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional
    public com.example.auth.dto.OpenAccountResponseDto openAccount(
            com.example.auth.dto.OpenAccountRequestDto request, String username) {

        AuthUsr authUsr = authUsrRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        CustDtl customer = authUsr.getCustomer();
        if (customer == null) {
            throw new IllegalStateException("No customer associated with this user");
        }

        // Only ACTIVE customers may open accounts
        if (customer.getCustomerStatus() == null
                || !"ACTIVE".equals(customer.getCustomerStatus().getStatusCode())) {
            throw new IllegalStateException(
                    "Customer must be ACTIVE to open an account. Current status: "
                    + (customer.getCustomerStatus() != null
                            ? customer.getCustomerStatus().getStatusCode() : "UNKNOWN"));
        }

        if (request.getProductId() == null) {
            throw new IllegalArgumentException("Product ID is required");
        }
        if (request.getBranchId() == null) {
            throw new IllegalArgumentException("Branch ID is required");
        }

        // Prevent duplicate: same customer + product + branch with a non-CLOSED account
        boolean duplicate = custAcctRepository
                .existsByCustomerAndProductIdAndBranchIdAndStatusNot(
                        customer, request.getProductId(), request.getBranchId(), "CLOSED");
        if (duplicate) {
            throw new IllegalStateException(
                    "An active account already exists for this customer with the same product and branch");
        }

        AuditData audit = auditUtil.createAudit("ACCOUNT_OPEN");

        CustAcct account = new CustAcct();
        account.setCustomer(customer);
        account.setProductId(request.getProductId());
        account.setBranchId(request.getBranchId());
        account.setAccountNumber(generateAccountNumber());
        account.setStatus("ACTIVE");
        account.setOpenedOn(LocalDate.now());

        // Audit fields
        account.setEffectiveDate(audit.getEffectiveDate());
        account.setCrudValue(audit.getCrudValue());
        account.setUserId(audit.getUserId());
        account.setWorkstationId(audit.getWorkstationId());
        account.setProgramId(audit.getProgramId());
        account.setHostTimestamp(audit.getHostTimestamp());
        account.setLocalTimestamp(audit.getLocalTimestamp());
        account.setAcceptanceTimestamp(audit.getAcceptanceTimestamp());
        account.setAcceptanceTimestampUtcOffset(audit.getAcceptanceTimestampUtcOffset());
        account.setUuid(audit.getUuid());

        CustAcct saved = custAcctRepository.save(account);

        com.example.auth.dto.OpenAccountResponseDto response = new com.example.auth.dto.OpenAccountResponseDto();
        response.setMessage("Account opened successfully");
        response.setAccountId(saved.getAccountId());
        response.setAccountNumber(saved.getAccountNumber());
        response.setStatus(saved.getStatus());
        response.setProductId(saved.getProductId());
        response.setBranchId(saved.getBranchId());
        response.setOpenedOn(saved.getOpenedOn());

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.example.auth.dto.OpenAccountResponseDto> getAccounts(String username) {
        AuthUsr authUsr = authUsrRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        CustDtl customer = authUsr.getCustomer();
        if (customer == null) {
            throw new IllegalStateException("No customer associated with this user");
        }

        return custAcctRepository.findByCustomer(customer).stream().map(acct -> {
            com.example.auth.dto.OpenAccountResponseDto dto = new com.example.auth.dto.OpenAccountResponseDto();
            dto.setAccountId(acct.getAccountId());
            dto.setAccountNumber(acct.getAccountNumber());
            dto.setStatus(acct.getStatus());
            dto.setProductId(acct.getProductId());
            dto.setBranchId(acct.getBranchId());
            dto.setOpenedOn(acct.getOpenedOn());
            return dto;
        }).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Generates a unique 16-digit account number.
     * Format: "10" prefix + timestamp-based digits + random suffix.
     * Retries if a collision is detected (extremely unlikely).
     */
    private String generateAccountNumber() {
        for (int attempt = 0; attempt < 5; attempt++) {
            long timestamp = System.currentTimeMillis();
            int random = (int) (Math.random() * 10000);
            String accountNumber = String.format("10%010d%04d", timestamp % 10000000000L, random);
            // Ensure exactly 16 digits
            if (accountNumber.length() > 16) {
                accountNumber = accountNumber.substring(0, 16);
            }
            if (!custAcctRepository.existsByAccountNumber(accountNumber)) {
                return accountNumber;
            }
        }
        throw new IllegalStateException("Unable to generate a unique account number after multiple attempts");
    }

    private void saveNameComponent(CustDtl customer, String nameTypeStr, String value, AuditData audit) {
        if (value == null || value.isBlank()) return;
        NmeTypMstr nameType = nmeTypMstrRepository.findByNameType(nameTypeStr)
                .orElseThrow(() -> new IllegalStateException("Invalid name type: " + nameTypeStr));
        CustNmeCmpnt cmpnt = custNmeCmpntRepository.findByCustomer(customer).stream()
                .filter(n -> n.getNameType() != null && nameTypeStr.equals(n.getNameType().getNameType()))
                .findFirst()
                .orElseGet(() -> {
                    CustNmeCmpnt n = new CustNmeCmpnt();
                    n.setCustomer(customer);
                    n.setNameType(nameType);
                    return n;
                });

        cmpnt.setNameTypeValue(value);
        cmpnt.setEffectiveDate(audit.getEffectiveDate());
        cmpnt.setCrudValue(cmpnt.getId() == null ? "C" : "U");
        cmpnt.setUserId(audit.getUserId());
        cmpnt.setWorkstationId(audit.getWorkstationId());
        cmpnt.setProgramId(audit.getProgramId());
        cmpnt.setHostTimestamp(audit.getHostTimestamp());
        cmpnt.setLocalTimestamp(audit.getLocalTimestamp());
        cmpnt.setAcceptanceTimestamp(audit.getAcceptanceTimestamp());
        cmpnt.setAcceptanceTimestampUtcOffset(audit.getAcceptanceTimestampUtcOffset());
        cmpnt.setUuid(audit.getUuid());
        custNmeCmpntRepository.save(cmpnt);
    }

    private void saveAddressComponent(CustDtl customer, String addrTypeStr, String value, AuditData audit) {
        if (value == null || value.isBlank()) return;
        AddrTypMstr addrType = addrTypMstrRepository.findByAddressType(addrTypeStr)
                .orElseThrow(() -> new IllegalStateException("Invalid address type: " + addrTypeStr));
        CustAddrCmpnt cmpnt = custAddrCmpntRepository.findByCustomer(customer).stream()
                .filter(a -> a.getAddressType() != null && addrTypeStr.equals(a.getAddressType().getAddressType()))
                .findFirst()
                .orElseGet(() -> {
                    CustAddrCmpnt a = new CustAddrCmpnt();
                    a.setCustomer(customer);
                    a.setAddressType(addrType);
                    return a;
                });

        cmpnt.setAddressTypeValue(value);
        cmpnt.setEffectiveDate(audit.getEffectiveDate());
        cmpnt.setCrudValue(cmpnt.getId() == null ? "C" : "U");
        cmpnt.setUserId(audit.getUserId());
        cmpnt.setWorkstationId(audit.getWorkstationId());
        cmpnt.setProgramId(audit.getProgramId());
        cmpnt.setHostTimestamp(audit.getHostTimestamp());
        cmpnt.setLocalTimestamp(audit.getLocalTimestamp());
        cmpnt.setAcceptanceTimestamp(audit.getAcceptanceTimestamp());
        cmpnt.setAcceptanceTimestampUtcOffset(audit.getAcceptanceTimestampUtcOffset());
        cmpnt.setUuid(audit.getUuid());
        custAddrCmpntRepository.save(cmpnt);
    }

    private void saveContactComponent(CustDtl customer, String contactTypeStr, String value, AuditData audit) {
        if (value == null || value.isBlank()) return;
        CntctTypMstr cntctType = cntctTypMstrRepository.findByContactType(contactTypeStr)
                .orElseThrow(() -> new IllegalStateException("Invalid contact type: " + contactTypeStr));
        CustCntctDtls cmpnt = custCntctDtlsRepository.findByCustomer(customer).stream()
                .filter(c -> c.getContactType() != null && contactTypeStr.equals(c.getContactType().getContactType()))
                .findFirst()
                .orElseGet(() -> {
                    CustCntctDtls c = new CustCntctDtls();
                    c.setCustomer(customer);
                    c.setContactType(cntctType);
                    return c;
                });

        cmpnt.setContactValue(value);
        cmpnt.setEffectiveDate(audit.getEffectiveDate());
        cmpnt.setCrudValue(cmpnt.getRecordId() == null ? "C" : "U");
        cmpnt.setUserId(audit.getUserId());
        cmpnt.setWorkstationId(audit.getWorkstationId());
        cmpnt.setProgramId(audit.getProgramId());
        cmpnt.setHostTimestamp(audit.getHostTimestamp());
        cmpnt.setLocalTimestamp(audit.getLocalTimestamp());
        cmpnt.setAcceptanceTimestamp(audit.getAcceptanceTimestamp());
        cmpnt.setAcceptanceTimestampUtcOffset(audit.getAcceptanceTimestampUtcOffset());
        cmpnt.setUuid(audit.getUuid());
        custCntctDtlsRepository.save(cmpnt);
    }

    private String buildFullName(String first, String middle, String last) {
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank()) sb.append(first.trim());
        if (middle != null && !middle.isBlank()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(middle.trim());
        }
        if (last != null && !last.isBlank()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(last.trim());
        }
        return sb.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * KYC accepts an optional mobile number, but when supplied it must be an
     * Indian 10-digit mobile number beginning with 6, 7, 8, or 9. The UI
     * formats numbers as +91 XXX XXX XXXX, so separators and the country code
     * are ignored for validation.
     */
    private void validateIndianMobileNumber(String mobileNumber) {
        if (mobileNumber == null || mobileNumber.isBlank()) {
            return;
        }

        String digits = mobileNumber.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }

        if (!digits.matches("[6-9]\\d{9}")) {
            throw new IllegalArgumentException(
                    "Mobile number must be a valid 10-digit Indian number starting with 6, 7, 8, or 9");
        }
    }

    private void validateMobileIsUnique(String mobileNumber, String username, CustDtl customer) {
        if (mobileNumber == null || mobileNumber.isBlank()) return;

        String requestedDigits = mobileDigits(mobileNumber);

        boolean usedByAnotherAuthUser = authUsrRepository.findAllByPhoneNumberIsNotNull().stream()
                .anyMatch(user -> !username.equals(user.getUsername())
                        && requestedDigits.equals(mobileDigits(user.getPhoneNumber())));
        if (usedByAnotherAuthUser) {
            throw new IllegalArgumentException("Mobile number is already registered by another user");
        }

        boolean usedByAnotherCustomer = custCntctDtlsRepository
                .findByContactTypeContactType("MOBILE").stream()
                .anyMatch(contact -> contact.getCustomer() != null
                        && !customer.getCustomerIdentifier().equals(contact.getCustomer().getCustomerIdentifier())
                        && requestedDigits.equals(mobileDigits(contact.getContactValue())));
        if (usedByAnotherCustomer) {
            throw new IllegalArgumentException("Mobile number is already registered by another customer");
        }
    }

    private String mobileDigits(String mobileNumber) {
        if (mobileNumber == null) return "";
        String digits = mobileNumber.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }
}

