package com.elice.nbbang.domain.user.service;


import com.elice.nbbang.domain.auth.dto.OAuth2Response;
import com.elice.nbbang.domain.auth.dto.request.PhoneCheckRequestDto;
import com.elice.nbbang.domain.auth.service.MessageService;
import com.elice.nbbang.domain.user.dto.reponse.UserResponse;
import com.elice.nbbang.domain.user.dto.request.PhoneNumberChangeRequestDto;
import com.elice.nbbang.domain.user.entity.User;
import com.elice.nbbang.domain.user.entity.UserRole;
import com.elice.nbbang.domain.user.exception.UserNotFoundException;
import com.elice.nbbang.domain.user.repository.UserRepository;
import com.elice.nbbang.global.exception.ErrorCode;
import com.elice.nbbang.global.util.UserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;


@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserUtil userUtil;
    private final MessageService messageService;

    public User findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    // 휴대폰 번호 변경
    public void changePhoneNumber(String email, PhoneNumberChangeRequestDto requestDto) {
        // 유저 정보 가져오기
        Optional<User> optionalUser = Optional.ofNullable(userRepository.findByEmail(email));

        if (!optionalUser.isPresent()) {
            throw new UserNotFoundException(ErrorCode.USER_NOT_FOUND);
        }

        User user = optionalUser.get();

        user.setPhoneNumber(requestDto.getNewPhoneNumber());
        userRepository.save(user);
    }

    // 휴대폰 번호 추가
    public boolean addPhoneNumberAfterSocialLogin(String email, String newPhoneNumber) {
        // Optional을 사용하여 User 객체를 안전하게 가져옴
        User user = Optional.ofNullable(userRepository.findByEmail(email))
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // 기존에 휴대폰 번호가 있는지 확인 후 업데이트
        if (user.getPhoneNumber() != null && !user.getPhoneNumber().isEmpty()) {
            throw new IllegalArgumentException("Phone number already exists");
        }

        // 새로운 휴대폰 번호를 설정하고 저장
        user.setPhoneNumber(newPhoneNumber);
        userRepository.save(user);

        return true;
    }


    // 회원 탈퇴
    public void deleteUser(String email) {
        User user = userRepository.findByEmail(email);

        user.setDeleted(true);
        userRepository.save(user);
    }

    public User findOrCreateUser(OAuth2Response oAuth2Response) {
        User user = userRepository.findByEmail(oAuth2Response.getEmail());
        if (user == null) {
            User newUser = new User();
            newUser.setEmail(oAuth2Response.getEmail());
            newUser.setNickname(oAuth2Response.getName());
            newUser.setRole(UserRole.ROLE_USER);
            user = userRepository.save(newUser);
        }
        return user;
    }

    public UserResponse getUserInfo() {

        String email = userUtil.getAuthenticatedUserEmail();
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new UserNotFoundException(ErrorCode.USER_NOT_FOUND);
        }
        boolean isAdmin = user.getRole() == UserRole.ROLE_ADMIN;

        return new UserResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(), user.getPhoneNumber(), isAdmin);
    }
}