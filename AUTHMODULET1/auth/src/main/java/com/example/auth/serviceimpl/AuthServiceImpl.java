package com.example.auth.serviceimpl;

import com.example.auth.dto.CreateEmployeeRequestDto;
import com.example.auth.dto.EmployeeResponseDto;
import com.example.auth.dto.LoginRequestDto;
import com.example.auth.dto.LoginResponseDto;
import com.example.auth.dto.PendingRegistrationDto;
import com.example.auth.dto.RegisterRequestDto;
import com.example.auth.dto.ResetPasswordRequestDto;
import com.example.auth.dto.UserResponseDto;
import com.example.auth.dto.UserUpdateRequestDto;
import com.example.auth.entity.AuthRole;
import com.example.auth.entity.AuthUsr;
import com.example.auth.entity.CustDtl;
import com.example.auth.entity.CustStsMstr;
import com.example.auth.entity.CustTypMstr;
import com.example.auth.entity.AddrTypMstr;
import com.example.auth.entity.CustAddrCmpnt;
import com.example.auth.repository.AuthRoleRepository;
import com.example.auth.repository.AuthUsrRepository;
import com.example.auth.repository.CustDtlRepository;
import com.example.auth.repository.CustStsMstrRepository;
import com.example.auth.repository.CustTypMstrRepository;
import com.example.auth.repository.AddrTypMstrRepository;
import com.example.auth.security.JwtService;
import com.example.auth.service.AuthService;
import com.example.auth.service.OtpPurpose;
import com.example.auth.service.OtpService;
import com.example.auth.util.AuditData;
import com.example.auth.util.AuditUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;

import java.security.Identity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthServiceImpl implements AuthService {

        private static final Pattern PASSWORD_PATTERN = Pattern.compile(
                        "(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*(),.?\":{}|<>]).{6,20}");

        /** Max consecutive failed logins before an account is locked. */
        private static final short MAX_FAILED_LOGIN_ATTEMPTS = 5;

        /** How long an account stays locked once the limit is hit. */
        private static final int ACCOUNT_LOCK_MINUTES = 15;

        /** Max failed logins from a single IP within the window before the IP is throttled. */
        private static final long MAX_FAILED_ATTEMPTS_PER_IP = 10L;

        /** Window (minutes) used for the per-IP failed attempt counter. */
        private static final int IP_WINDOW_MINUTES = 15;

        private final AuthUsrRepository authUsrRepository;
        private final CustDtlRepository custDtlRepository;
        private final AuthRoleRepository authRoleRepository;
        private final OtpService otpService;
        private final PasswordEncoder passwordEncoder;
        private final com.example.auth.repository.LoginAuditRepository loginAuditRepository;

        @Autowired
        private AddrTypMstrRepository addrTypMstrRepository;

        @Autowired
        private AuditUtil auditUtil;

        @Autowired
        private CustTypMstrRepository custTypMstrRepository;

        @Autowired
        private CustStsMstrRepository custStsMstrRepository;

        @Autowired
        private com.example.auth.repository.CustNmeCmpntRepository custNmeCmpntRepository;

        @Autowired
        private com.example.auth.service.LoginAuditService loginAuditService;

        @Autowired
        private com.example.auth.repository.CustIdfnRepository custIdfnRepository;

        @Autowired
        private com.example.auth.repository.CustAddrCmpntRepository custAddrCmpntRepository;

        @Autowired
        private com.example.auth.repository.CustCntctDtlsRepository custCntctDtlsRepository;

        private final Map<String, PendingRegistrationDto> pendingRegistrations = new ConcurrentHashMap<>();

        @Autowired
        JwtService jwtService;

        @Autowired
        public AuthServiceImpl(
                        AuthUsrRepository authUsrRepository,
                        CustDtlRepository custDtlRepository,
                        AuthRoleRepository authRoleRepository,
                        OtpService otpService,
                        PasswordEncoder passwordEncoder,
                        com.example.auth.service.LoginAuditService loginAuditService,
                        JwtService jwtService,
                        com.example.auth.repository.LoginAuditRepository loginAuditRepository) {

                this.authUsrRepository = authUsrRepository;
                this.custDtlRepository = custDtlRepository;
                this.authRoleRepository = authRoleRepository;
                this.otpService = otpService;
                this.passwordEncoder = passwordEncoder;
                this.loginAuditService = loginAuditService;
                this.jwtService = jwtService;
                this.loginAuditRepository = loginAuditRepository;
        }

        @Override
        public void register(RegisterRequestDto request) {

                // Email OR phone is required
                if ((request.getEmail() == null || request.getEmail().isBlank())
                                && (request.getPhoneNumber() == null
                                                || request.getPhoneNumber().isBlank())) {

                        throw new IllegalArgumentException(
                                        "Either email or phone number is required");
                }

                validatePassword(request.getPassword());

                // Name is required
                if (request.getName() == null
                                || request.getName().isBlank()) {

                        throw new IllegalArgumentException(
                                        "Name is required");
                }

                // Check existing email
                if (request.getEmail() != null
                                && !request.getEmail().isBlank()
                                && authUsrRepository.existsByEmail(request.getEmail())) {

                        throw new IllegalArgumentException(
                                        "Email is already registered");
                }

                // Check existing phone
                if (request.getPhoneNumber() != null
                                && !request.getPhoneNumber().isBlank()
                                && authUsrRepository.existsByPhoneNumber(
                                                request.getPhoneNumber())) {

                        throw new IllegalArgumentException(
                                        "Phone number is already registered");
                }

                /*
                 * Use email as identifier if available.
                 * Otherwise use phone number.
                 */
                String identifier = getIdentifier(request);

                // Temporarily store registration details
                PendingRegistrationDto pendingRegistration = new PendingRegistrationDto(
                                request.getName(),
                                request.getEmail(),
                                request.getPhoneNumber(),
                                request.getPassword());

                pendingRegistrations.put(identifier, pendingRegistration);

                // Generate OTP
                otpService.generateAndSendOtp(identifier, OtpPurpose.REGISTRATION);
        }

        @Override
        @Transactional
        public boolean verifyRegistrationOtp(
                        String identifier,
                        String otp) {

                // Verify OTP first
                boolean verified = otpService.verifyOtp(identifier, otp);

                if (!verified) {
                        return false;
                }

                // Get pending registration
                PendingRegistrationDto pendingRegistration = pendingRegistrations.get(identifier);

                if (pendingRegistration == null) {
                        throw new IllegalArgumentException(
                                        "Registration request not found or expired");
                }

                // Get CUSTOMER role
                AuthRole customerRole = authRoleRepository
                                .findByRoleCode("CUSTOMER")
                                .orElseThrow(() -> new IllegalStateException(
                                                "CUSTOMER role not found"));

                // Get INDIVIDUAL customer type
                CustTypMstr customerType = custTypMstrRepository
                                .findByTypeCode("INDIVIDUAL")
                                .orElseThrow(() -> new IllegalStateException(
                                                "INDIVIDUAL customer type not found"));

                // Get KYC PENDING customer status
                CustStsMstr customerStatus = custStsMstrRepository
                                .findByStatusCode("KYC_PENDING")
                                .orElseThrow(() -> new IllegalStateException(
                                                "KYC_PENDING customer status not found"));

                /*
                 * Create audit data
                 */
                AuditData audit = auditUtil.createAudit("REGISTRATION");

                /*
                 * Create customer
                 */
                CustDtl customer = new CustDtl();

                customer.setFullName(
                                pendingRegistration.getName());

                customer.setCustomerType(customerType);

                customer.setCustomerStatus(customerStatus);

                // Customer audit fields
                customer.setEffectiveDate(
                                audit.getEffectiveDate());

                customer.setCrudValue(
                                audit.getCrudValue());

                customer.setUserId(
                                audit.getUserId());

                customer.setWorkstationId(
                                audit.getWorkstationId());

                customer.setProgramId(
                                audit.getProgramId());

                customer.setHostTimestamp(
                                audit.getHostTimestamp());

                customer.setLocalTimestamp(
                                audit.getLocalTimestamp());

                customer.setAcceptanceTimestamp(
                                audit.getAcceptanceTimestamp());

                customer.setAcceptanceTimestampUtcOffset(
                                audit.getAcceptanceTimestampUtcOffset());

                customer.setUuid(
                                audit.getUuid());

                /*
                 * Save customer.
                 *
                 * CSTID_IDFR is generated by the database.
                 */
                CustDtl savedCustomer = custDtlRepository.save(customer);

                /*
                 * Create authentication user
                 */
                AuthUsr authUsr = new AuthUsr();

                // Link AUTH_USR to the newly created customer
                authUsr.setCustomer(savedCustomer);

                /*
                 * USERNAME is mandatory.
                 *
                 * Use email when available,
                 * otherwise use phone number.
                 */
                String username;

                if (pendingRegistration.getEmail() != null
                                && !pendingRegistration.getEmail().isBlank()) {

                        username = pendingRegistration
                                        .getEmail()
                                        .trim()
                                        .toLowerCase();

                } else {

                        username = pendingRegistration
                                        .getPhoneNumber()
                                        .trim();
                }

                authUsr.setUsername(username);

                // Email can be null
                authUsr.setEmail(
                                pendingRegistration.getEmail());

                // Phone can be null
                authUsr.setPhoneNumber(
                                pendingRegistration.getPhoneNumber());

                // Hash password before storing
                authUsr.setPasswordHash(
                                passwordEncoder.encode(
                                                pendingRegistration.getPassword()));
                authUsr.setPasswordChangedAt(LocalDateTime.now());

                // Assign CUSTOMER role
                authUsr.setRole(customerRole);

                // Initial login state
                authUsr.setFailedLoginAttempts(
                                (short) 0);

                /*
                 * Apply audit data
                 */
                authUsr.setEffectiveDate(
                                audit.getEffectiveDate());

                authUsr.setCrudValue(
                                audit.getCrudValue());

                authUsr.setAuditUserId(
                                audit.getUserId());

                authUsr.setWorkstationId(
                                audit.getWorkstationId());

                authUsr.setProgramId(
                                audit.getProgramId());

                authUsr.setHostTimestamp(
                                audit.getHostTimestamp());

                authUsr.setLocalTimestamp(
                                audit.getLocalTimestamp());

                authUsr.setAcceptanceTimestamp(
                                audit.getAcceptanceTimestamp());

                authUsr.setAcceptanceTimestampUtcOffset(
                                audit.getAcceptanceTimestampUtcOffset());

                authUsr.setUuid(
                                audit.getUuid());

                // Save authentication user
                authUsrRepository.save(authUsr);

                // Registration completed successfully
                pendingRegistrations.remove(identifier);

                return true;
        }

        private String getIdentifier(RegisterRequestDto request) {

                if (request.getEmail() != null
                                && !request.getEmail().isBlank()) {

                        return request.getEmail()
                                        .trim()
                                        .toLowerCase();
                }

                return request.getPhoneNumber()
                                .trim();
        }

        // @Override
        // public String login(LoginRequestDto request) {
        //
        // if (request.getUsername() == null
        // || request.getUsername().isBlank()) {
        //
        // throw new IllegalArgumentException(
        // "Username is required");
        // }
        //
        // if (request.getPassword() == null
        // || request.getPassword().isBlank()) {
        //
        // throw new IllegalArgumentException(
        // "Password is required");
        // }
        //
        // AuthUsr authUsr = authUsrRepository
        // .findByUsername(
        // request.getUsername().trim())
        // .orElseThrow(() ->
        // new IllegalArgumentException(
        // "Invalid username or password"));
        //
        // if (!passwordEncoder.matches(
        // request.getPassword(),
        // authUsr.getPasswordHash())) {
        //
        // throw new IllegalArgumentException(
        // "Invalid username or password");
        // }
        //
        // return jwtService.generateToken(authUsr);
        // }

        @Override
        public LoginResponseDto login(LoginRequestDto request, String ipAddress, String userAgent) {

                if (request.getUsername() == null
                                || request.getUsername().isBlank()) {
                        throw new IllegalArgumentException("Username is required");
                }

                if (request.getPassword() == null
                                || request.getPassword().isBlank()) {
                        throw new IllegalArgumentException("Password is required");
                }

                String loginIdentifier = request.getUsername().trim();
                String normalizedIdentifier = loginIdentifier.toLowerCase(Locale.ROOT);
                AuthUsr authUsr = Optional.ofNullable(authUsrRepository.findByUsername(normalizedIdentifier))
                                .orElse(Optional.empty())
                                .orElseGet(() -> Optional.ofNullable(authUsrRepository.findByEmail(normalizedIdentifier))
                                                .orElse(Optional.empty())
                                                .orElseGet(() -> Optional.ofNullable(authUsrRepository.findByPhoneNumber(loginIdentifier))
                                                                .orElse(Optional.empty())
                                                                .orElse(null)));

                // Check account lock first.
                if (authUsr != null && isAccountLocked(authUsr)) {
                        // Count this as an IP-level failure too (locked accounts being brute-force tried).
                        loginAuditService.recordAudit(authUsr, "FAILED", "ACCOUNT_LOCKED", ipAddress, userAgent);
                        if (isIpThrottled(ipAddress)) {
                                throw new IllegalArgumentException(
                                                "Too many failed attempts. Please try again later.");
                        }
                        throw new IllegalArgumentException(
                                        "Account is temporarily locked. Please try again later.");
                }

                if (authUsr != null && passwordEncoder.matches(request.getPassword(), authUsr.getPasswordHash())) {
                        // Valid credentials — clear attempts and allow login.
                        clearFailedAttempts(authUsr);
                        loginAuditService.recordAudit(authUsr, "SUCCESS", null, ipAddress, userAgent);

                        String accessToken = jwtService.generateToken(authUsr);
                        String refreshToken = jwtService.generateRefreshToken(authUsr);
                        return new LoginResponseDto(accessToken, refreshToken);
                }

                // At this point either user missing or password wrong.
                if (authUsr == null) {
                        loginAuditService.recordAudit(null, "FAILED", "UNKNOWN_USERNAME", ipAddress, userAgent);
                        if (isIpThrottled(ipAddress)) {
                                throw new IllegalArgumentException(
                                                "Too many failed attempts. Please try again later.");
                        }
                        throw new IllegalArgumentException("Invalid username or password");
                }

                // Wrong password for known user.
                registerFailedAttempt(authUsr);
                loginAuditService.recordAudit(authUsr, "FAILED", "INCORRECT_PASSWORD", ipAddress, userAgent);

                // After registering failure, check if IP should now throttle.
                if (isIpThrottled(ipAddress)) {
                        loginAuditService.recordAudit(authUsr, "FAILED", "IP_THROTTLED", ipAddress, userAgent);
                        throw new IllegalArgumentException(
                                        "Too many failed attempts. Please try again later.");
                }

                throw new IllegalArgumentException("Invalid username or password");
        }

        /**
         * True when the account is still inside its lock window.
         */
        private boolean isAccountLocked(AuthUsr authUsr) {
                LocalDateTime lockedUntil = authUsr.getLockedUntil();
                return lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil);
        }

        /**
         * Increments the failed attempt counter and locks the account once the
         * limit is reached.
         */
        @Transactional
        public void registerFailedAttempt(AuthUsr authUsr) {

                short attempts = authUsr.getFailedLoginAttempts() == null
                                ? (short) 0
                                : authUsr.getFailedLoginAttempts();

                attempts++;

                authUsr.setFailedLoginAttempts(attempts);

                if (attempts >= MAX_FAILED_LOGIN_ATTEMPTS) {
                        authUsr.setLockedUntil(
                                        LocalDateTime.now().plusMinutes(ACCOUNT_LOCK_MINUTES));
                }

                authUsrRepository.save(authUsr);
        }

        /**
         * Resets the counter, clears any lock and stamps the login time.
         */
        @Transactional
        public void clearFailedAttempts(AuthUsr authUsr) {
                authUsr.setFailedLoginAttempts((short) 0);
                authUsr.setLockedUntil(null);
                authUsr.setLastLoginAt(LocalDateTime.now());
                authUsrRepository.save(authUsr);
        }

        /**
         * True when this IP has produced too many failed logins inside the
         * rolling window. Counted from the existing login_audit table.
         */
        private boolean isIpThrottled(String ipAddress) {

                if (ipAddress == null || ipAddress.isBlank()) {
                        return false;
                }

                LocalDateTime since = LocalDateTime.now().minusMinutes(IP_WINDOW_MINUTES);

                long failed = loginAuditRepository.countFailedByIpInWindow(ipAddress, since);

                return failed >= MAX_FAILED_ATTEMPTS_PER_IP;
        }

        @Override
        public LoginResponseDto refreshToken(String refreshToken) {

                if (refreshToken == null || refreshToken.isBlank()) {
                        throw new IllegalArgumentException(
                                        "Refresh token is required");
                }

                String username = jwtService.extractUsername(refreshToken);

                AuthUsr authUsr = authUsrRepository
                                .findByUsername(username)
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Invalid refresh token"));

                if (!jwtService.isTokenValid(refreshToken, username)) {
                        throw new IllegalArgumentException(
                                        "Invalid or expired refresh token");
                }

                String newAccessToken = jwtService.generateToken(authUsr);

                return new LoginResponseDto(
                                newAccessToken,
                                refreshToken);
        }

        @Override
        public void forgotPassword(String identifier) {

                if (identifier == null || identifier.isBlank())
                        throw new IllegalArgumentException("Email or Phone number is required");

                AuthUsr user = findUserByRecoveryIdentifier(identifier.trim());
                String email = user.getEmail();
                if (email == null || email.isBlank()) {
                        throw new IllegalArgumentException("No email address is registered for this account");
                }

                otpService.generateAndSendOtp(email.trim().toLowerCase(), OtpPurpose.PASSWORD_RESET);
        }

        @Override
        @Transactional
        public void resetPassword(@RequestBody ResetPasswordRequestDto request) {

                String identifier = request.getIdentifier();
                if (identifier == null || identifier.isBlank()) {
                        throw new IllegalArgumentException("Email or phone number is required");
                }
                identifier = identifier.trim();

                String otp = request.getOtp();
                if (otp == null || otp.isBlank()) {
                        otp = request.getToken();
                }
                if (otp == null || otp.isBlank()) {
                        throw new IllegalArgumentException("Reset token / OTP is required");
                }

                validatePassword(request.getNewPassword());
                String confirmPassword = request.getConfirmPassword();
                if (confirmPassword == null || !request.getNewPassword().equals(confirmPassword)) {
                        throw new IllegalArgumentException("New password not matching confirmation password");
                }

                AuthUsr authUsr = findUserByRecoveryIdentifier(identifier);
                String email = authUsr.getEmail();
                if (email == null || email.isBlank()) {
                        throw new IllegalArgumentException("No email address is registered for this account");
                }

                boolean verified = otpService.verifyOtp(email.trim().toLowerCase(), otp);

                if (!verified)
                        throw new IllegalArgumentException("Invalid or expired OTP");

                authUsr.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
                authUsr.setPasswordChangedAt(LocalDateTime.now());

                authUsrRepository.save(authUsr);
        }

        private AuthUsr findUserByRecoveryIdentifier(String identifier) {
                String normalized = identifier.trim();
                if (normalized.contains("@")) {
                        return authUsrRepository.findByEmail(normalized.toLowerCase())
                                        .orElseThrow(() -> new IllegalArgumentException("No account found with this email"));
                }

                return authUsrRepository.findByUsername(normalized)
                                .or(() -> authUsrRepository.findByPhoneNumber(normalized))
                                .orElseThrow(() -> new IllegalArgumentException("No account found for this identifier"));
        }

        private void validatePassword(String password) {
                if (password == null || password.isBlank()) {
                        throw new IllegalArgumentException("Password is required");
                }
                if (!PASSWORD_PATTERN.matcher(password).matches()) {
                        throw new IllegalArgumentException(
                                        "Password must be 6-20 characters and include a letter, a number, and a special character");
                }
        }

        @Override
        public void logout(String token, String username) {
                if (token == null || token.isBlank()) {
                        return;
                }

                jwtService.invalidateToken(token);

                AuthUsr authUsr = authUsrRepository.findByUsername(username).orElse(null);
                if (authUsr != null) {
                        loginAuditService.updateLogoutTimestamp(authUsr);
                }
        }

        @Override
        @Transactional(readOnly = true)
        public UserResponseDto getUserProfile(String username) {
                AuthUsr authUsr = authUsrRepository.findByUsername(username)
                                .orElseThrow(() -> new IllegalArgumentException("User not found"));

                UserResponseDto dto = new UserResponseDto();
                dto.setUsername(authUsr.getUsername());
                dto.setEmail(authUsr.getEmail());
                dto.setPhoneNumber(authUsr.getPhoneNumber());
                if (authUsr.getRole() != null) {
                        dto.setRole(authUsr.getRole().getRoleCode());
                }

                CustDtl customer = authUsr.getCustomer();
                if (customer != null) {
                        dto.setCustomerId(customer.getCustomerIdentifier());
                        dto.setDateOfBirth(customer.getDateOfBirth());
                        dto.setGender(customer.getGender());
                        dto.setPreferredLanguage(customer.getPreferredLanguage());
                        dto.setCountryOfOrigin(customer.getCountryOfOrigin());
                        if (customer.getCustomerStatus() != null) {
                                dto.setCustomerStatus(customer.getCustomerStatus().getStatusCode());
                        }

                        // Name - First try to get fullName from cust_dtl table
                        if (customer.getFullName() != null) {
                            dto.setFullName(customer.getFullName());
                            // Parse full name into first and last name components
                            String fullName = customer.getFullName();
                            String[] nameParts = fullName.split(" ", 2);
                            if (nameParts.length >= 1) {
                                dto.setFirstName(nameParts[0]);
                            }
                            if (nameParts.length >= 2) {
                                // Split remaining part to get last name (handles multiple middle names)
                                String[] remainingParts = nameParts[1].split(" ", 2);
                                if (remainingParts.length >= 1) {
                                    dto.setLastName(remainingParts[0]);
                                }
                                if (remainingParts.length >= 2) {
                                    // Middle name handling - join all remaining parts except last as middle name
                                    StringBuilder middleName = new StringBuilder();
                                    for (int i = 1; i < remainingParts.length - 1; i++) {
                                        if (middleName.length() > 0) middleName.append(" ");
                                        middleName.append(remainingParts[i]);
                                    }
                                    if (middleName.length() > 0) {
                                        dto.setMiddleName(middleName.toString());
                                    }
                                    dto.setLastName(remainingParts[remainingParts.length - 1]);
                                }
                            }
                        } else {
                            // Fallback to individual name components from custNmeCmpnt table
                            java.util.List<com.example.auth.entity.CustNmeCmpnt> names = custNmeCmpntRepository
                                            .findByCustomer(customer);
                            for (com.example.auth.entity.CustNmeCmpnt n : names) {
                                String type = n.getNameType().getNameType();
                                if ("FIRST_NAME".equals(type))
                                    dto.setFirstName(n.getNameTypeValue());
                                else if ("MIDDLE_NAME".equals(type))
                                    dto.setMiddleName(n.getNameTypeValue());
                                else if ("LAST_NAME".equals(type))
                                    dto.setLastName(n.getNameTypeValue());
                            }
                        }

                        // Identification
                        java.util.List<com.example.auth.entity.CustIdfn> idfns = custIdfnRepository
                                        .findByCustomer(customer);
                        if (!idfns.isEmpty()) {
                                com.example.auth.entity.CustIdfn idfn = idfns.get(0); // Take the first one
                                dto.setIdentificationType(idfn.getIdentificationType().getIdentificationType());
                                String idNum = idfn.getIdentificationItem();
                                dto.setIdentificationNumber(
                                                com.example.auth.util.MaskingUtil.maskIdentificationNumber(idNum));
                        }

                        // Address
                        java.util.List<com.example.auth.entity.CustAddrCmpnt> addrs = custAddrCmpntRepository
                                        .findByCustomer(customer);
                        for (com.example.auth.entity.CustAddrCmpnt a : addrs) {
                                String type = a.getAddressType().getAddressType();
                                if ("ADDRESS_LINE_1".equals(type))
                                        dto.setAddressLine1(a.getAddressTypeValue());
                                else if ("ADDRESS_LINE_2".equals(type))
                                        dto.setAddressLine2(a.getAddressTypeValue());
                                else if ("CITY".equals(type))
                                        dto.setCity(a.getAddressTypeValue());
                                else if ("STATE".equals(type))
                                        dto.setState(a.getAddressTypeValue());
                                else if ("COUNTRY".equals(type))
                                        dto.setCountry(a.getAddressTypeValue());
                                else if ("POSTAL_CODE".equals(type))
                                        dto.setPostalCode(a.getAddressTypeValue());
                        }

                        // Contact Information (masked)
                        // Use AuthUsr as the source of truth for email and phone since PUT /users/me
                        // updates AuthUsr
                        dto.setMaskedEmail(com.example.auth.util.MaskingUtil.maskEmail(authUsr.getEmail()));
                        dto.setMaskedMobileNumber(
                                        com.example.auth.util.MaskingUtil.maskMobileNumber(authUsr.getPhoneNumber()));

                        java.util.List<com.example.auth.entity.CustCntctDtls> contacts = custCntctDtlsRepository
                                        .findByCustomer(customer);
                        for (com.example.auth.entity.CustCntctDtls c : contacts) {
                                String type = c.getContactType().getContactType();
                                String val = c.getContactValue();
                                if ("MOBILE".equals(type) && val != null && (authUsr.getPhoneNumber() == null
                                                || authUsr.getPhoneNumber().isBlank())) {
                                        dto.setPhoneNumber(val);
                                        dto.setMaskedMobileNumber(
                                                        com.example.auth.util.MaskingUtil.maskMobileNumber(val));
                                } else if ("EMAIL".equals(type) && val != null
                                                && (authUsr.getEmail() == null || authUsr.getEmail().isBlank())) {
                                        dto.setEmail(val);
                                        dto.setMaskedEmail(com.example.auth.util.MaskingUtil.maskEmail(val));
                                }
                        }
                }

                return dto;
        }

        @Override
        @Transactional
        public UserResponseDto updateUserProfile(String username, UserUpdateRequestDto request) {
                AuthUsr authUsr = authUsrRepository.findByUsername(username)
                                .orElseThrow(() -> new IllegalArgumentException("User not found"));

                // Update phone if provided and not blank, and check uniqueness if changed
                if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
                        String newPhone = normalizeMobileNumber(request.getPhoneNumber());
                        validateIndianMobileNumber(newPhone);
                        if (!newPhone.equals(authUsr.getPhoneNumber())) {
                                if (authUsrRepository.existsByPhoneNumber(newPhone)) {
                                        throw new IllegalArgumentException(
                                                        "Phone number is already registered by another user");
                                }
                                authUsr.setPhoneNumber(newPhone);
                        }
                }

                CustDtl customer = authUsr.getCustomer();
                if (customer != null) {
                        String first = trimToNull(request.getFirstName());
                        String middle = trimToNull(request.getMiddleName());
                        String last = trimToNull(request.getLastName());
                        if (first != null || middle != null || last != null) {
                                customer.setFullName(java.util.stream.Stream.of(first, middle, last)
                                                .filter(java.util.Objects::nonNull)
                                                .collect(java.util.stream.Collectors.joining(" ")));
                                custDtlRepository.save(customer);
                        }

                        AuditData audit = auditUtil.updateAudit("USER_PROFILE_UPDATE");
                        saveAddressComponent(customer, "ADDRESS_LINE_1", request.getAddressLine1(), audit);
                        saveAddressComponent(customer, "ADDRESS_LINE_2", request.getAddressLine2(), audit);
                        saveAddressComponent(customer, "CITY", request.getCity(), audit);
                        saveAddressComponent(customer, "STATE", request.getState(), audit);
                        saveAddressComponent(customer, "COUNTRY", request.getCountry(), audit);
                        saveAddressComponent(customer, "POSTAL_CODE", request.getPostalCode(), audit);
                }

                authUsrRepository.save(authUsr);
                return getUserProfile(username);
        }

        private String trimToNull(String value) {
                if (value == null || value.isBlank()) return null;
                return value.trim();
        }

        private void validateIndianMobileNumber(String mobileNumber) {
                String digits = mobileNumber.replaceAll("\\D", "");
                if (digits.length() == 12 && digits.startsWith("91")) {
                        digits = digits.substring(2);
                }
                if (!digits.matches("[6-9]\\d{9}")) {
                        throw new IllegalArgumentException(
                                        "Mobile number must be a valid 10-digit Indian number starting with 6, 7, 8, or 9");
                }
        }

        private String normalizeMobileNumber(String mobileNumber) {
                String digits = mobileNumber.replaceAll("\\D", "");
                if (digits.length() == 12 && digits.startsWith("91")) {
                        return digits.substring(2);
                }
                return digits;
        }

        private void saveAddressComponent(CustDtl customer, String typeName, String value, AuditData audit) {
                if (value == null || value.isBlank()) return;
                AddrTypMstr type = addrTypMstrRepository.findByAddressType(typeName)
                                .orElseThrow(() -> new IllegalStateException("Invalid address type: " + typeName));
                CustAddrCmpnt component = custAddrCmpntRepository.findByCustomer(customer).stream()
                                .filter(a -> a.getAddressType() != null
                                                && typeName.equals(a.getAddressType().getAddressType()))
                                .findFirst().orElseGet(CustAddrCmpnt::new);
                component.setCustomer(customer);
                component.setAddressType(type);
                component.setAddressTypeValue(value.trim());
                component.setEffectiveDate(audit.getEffectiveDate());
                component.setCrudValue("U");
                component.setUserId(audit.getUserId());
                component.setWorkstationId(audit.getWorkstationId());
                component.setProgramId(audit.getProgramId());
                component.setHostTimestamp(audit.getHostTimestamp());
                component.setLocalTimestamp(audit.getLocalTimestamp());
                component.setAcceptanceTimestamp(audit.getAcceptanceTimestamp());
                component.setAcceptanceTimestampUtcOffset(audit.getAcceptanceTimestampUtcOffset());
                component.setUuid(audit.getUuid());
                custAddrCmpntRepository.save(component);
        }

        /** Creates a persisted bank staff account using the existing STAFF role. */
        @Override
        @Transactional
        public EmployeeResponseDto createEmployee(String callerUsername,
                CreateEmployeeRequestDto request) {

                ensureEmployeeAdmin(callerUsername);

                if (request.getUsername() == null
                                || request.getUsername().isBlank()) {

                        throw new IllegalArgumentException("Username is required");
                }

                if (request.getPassword() == null
                                || request.getPassword().isBlank()) {

                        throw new IllegalArgumentException("Password is required");
                }

                String username = request.getUsername().trim().toLowerCase(Locale.ROOT);

                if ((request.getEmail() == null || request.getEmail().isBlank())
                                && (request.getPhoneNumber() == null || request.getPhoneNumber().isBlank())) {
                        throw new IllegalArgumentException("Email or phone number is required");
                }

                if (existsUsernameOrEmailOrPhone(username, request.getEmail(),
                                request.getPhoneNumber())) {

                        throw new IllegalArgumentException("Username, email, or phone already exists");
                }

                String roleCode = request.getRoleCode() == null || request.getRoleCode().isBlank()
                                ? "STAFF"
                                : request.getRoleCode().trim().toUpperCase(Locale.ROOT);

                if (!"STAFF".equals(roleCode)) {
                        throw new IllegalArgumentException("Employee role must be STAFF");
                }

                AuthRole employeeRole = authRoleRepository.findByRoleCode("STAFF")
                                .orElseThrow(() -> new IllegalStateException("STAFF role not found"));

                AuditData audit = auditUtil.createAudit("ADMIN_CREATE_EMPLOYEE");

                // Every authenticated account must have a customer identifier because
                // auth_usr.CSTID_IDFR is the shared identity link used by downstream services.
                CustDtl staffCustomer = new CustDtl();
                staffCustomer.setCustomerType(custTypMstrRepository.findByTypeCode("INDIVIDUAL")
                                .orElseThrow(() -> new IllegalStateException("INDIVIDUAL customer type not found")));
                staffCustomer.setCustomerStatus(custStsMstrRepository.findByStatusCode("ACTIVE")
                                .orElseThrow(() -> new IllegalStateException("ACTIVE customer status not found")));
                staffCustomer.setFullName(username);
                staffCustomer.setPreferredLanguage("EN");
                staffCustomer.setCountryOfOrigin("IN");
                staffCustomer.setEffectiveDate(audit.getEffectiveDate());
                staffCustomer.setCrudValue(audit.getCrudValue());
                staffCustomer.setUserId(audit.getUserId());
                staffCustomer.setWorkstationId(audit.getWorkstationId());
                staffCustomer.setProgramId(audit.getProgramId());
                staffCustomer.setHostTimestamp(audit.getHostTimestamp());
                staffCustomer.setLocalTimestamp(audit.getLocalTimestamp());
                staffCustomer.setAcceptanceTimestamp(audit.getAcceptanceTimestamp());
                staffCustomer.setAcceptanceTimestampUtcOffset(audit.getAcceptanceTimestampUtcOffset());
                staffCustomer.setUuid(audit.getUuid());
                staffCustomer = custDtlRepository.save(staffCustomer);

                AuthUsr employee = new AuthUsr();
                employee.setCustomer(staffCustomer);
                employee.setUsername(username);
                if (request.getEmail() != null && !request.getEmail().isBlank()) {
                        employee.setEmail(request.getEmail().trim().toLowerCase(Locale.ROOT));
                }
                if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
                        employee.setPhoneNumber(request.getPhoneNumber().trim());
                }
                employee.setPasswordHash(passwordEncoder.encode(request.getPassword()));
                employee.setPasswordChangedAt(LocalDateTime.now());
                employee.setRole(employeeRole);
                employee.setFailedLoginAttempts((short) 0);

                employee.setEffectiveDate(audit.getEffectiveDate());
                employee.setCrudValue(audit.getCrudValue());
                employee.setAuditUserId(audit.getUserId());
                employee.setWorkstationId(audit.getWorkstationId());
                employee.setProgramId(audit.getProgramId());
                employee.setHostTimestamp(audit.getHostTimestamp());
                employee.setLocalTimestamp(audit.getLocalTimestamp());
                employee.setAcceptanceTimestamp(audit.getAcceptanceTimestamp());
                employee.setAcceptanceTimestampUtcOffset(audit.getAcceptanceTimestampUtcOffset());
                employee.setUuid(audit.getUuid());

                employee = authUsrRepository.save(employee);

                EmployeeResponseDto response = new EmployeeResponseDto();
                response.setUserId(employee.getUserId());
                response.setUsername(employee.getUsername());
                response.setEmail(employee.getEmail());
                response.setPhoneNumber(employee.getPhoneNumber());
                response.setRole(employee.getRole().getRoleCode());

                return response;
        }

        @Override
        @Transactional(readOnly = true)
        public List<EmployeeResponseDto> listEmployees(String callerUsername) {
                ensureEmployeeAdmin(callerUsername);
                return authUsrRepository.findByRoleRoleCodeIn(List.of("STAFF"))
                                .stream()
                                .map(this::toEmployeeResponse)
                                .toList();
        }

        private void ensureEmployeeAdmin(String callerUsername) {
                AuthUsr caller = authUsrRepository.findByUsername(callerUsername)
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Authenticated caller not found"));

                String callerRole = caller.getRole() == null ? null : caller.getRole().getRoleCode();
                if (!Set.of("ADMIN", "HEAD_ADMIN").contains(callerRole)) {
                        throw new org.springframework.security.access.AccessDeniedException(
                                        "Only ADMIN users may manage employees");
                }
        }


        private EmployeeResponseDto toEmployeeResponse(AuthUsr employee) {
                EmployeeResponseDto response = new EmployeeResponseDto();
                response.setUserId(employee.getUserId());
                response.setUsername(employee.getUsername());
                response.setEmail(employee.getEmail());
                response.setPhoneNumber(employee.getPhoneNumber());
                response.setRole(employee.getRole() == null ? null : employee.getRole().getRoleCode());
                return response;
        }

        private boolean existsUsernameOrEmailOrPhone(String username, String email,
                String phoneNumber) {

                if (authUsrRepository.existsByUsername(username)
                                || authUsrRepository.existsByEmail(email)
                                || authUsrRepository.existsByPhoneNumber(phoneNumber)) {

                        return true;
                }

                if (authUsrRepository.findByUsername(username).map(u -> {
                        boolean matchEmail = (email != null && !email.isBlank())
                                        && email.equalsIgnoreCase(u.getEmail());
                        boolean matchPhone = (phoneNumber != null && !phoneNumber.isBlank())
                                        && phoneNumber.trim().equalsIgnoreCase(u.getPhoneNumber());
                        return matchEmail || matchPhone;
                }).orElse(false)) {

                        return true;
                }

                return false;
        }

        @Override
        @Transactional(readOnly = true)
        public boolean checkUsernameExists(String username) {
                if (username == null || username.trim().isBlank()) {
                        return false;
                }
                String trimmed = username.trim();
                return authUsrRepository.existsByUsername(trimmed)
                                || authUsrRepository.existsByUsername(trimmed.toLowerCase())
                                || authUsrRepository.existsByEmail(trimmed);
        }
}
