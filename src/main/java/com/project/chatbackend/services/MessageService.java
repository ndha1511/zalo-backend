package com.project.chatbackend.services;

import com.project.chatbackend.exceptions.DataNotFoundException;
import com.project.chatbackend.exceptions.PermissionAccessDenied;
import com.project.chatbackend.models.*;
import com.project.chatbackend.repositories.GroupRepository;
import com.project.chatbackend.repositories.MessageRepository;
import com.project.chatbackend.repositories.RoomRepository;
import com.project.chatbackend.repositories.UserRepository;
import com.project.chatbackend.requests.CallRequest;
import com.project.chatbackend.requests.ChatImageGroupRequest;
import com.project.chatbackend.requests.ChatRequest;
import com.project.chatbackend.responses.UserNotify;
import com.project.chatbackend.responses.MessageResponse;
import com.project.chatbackend.utils.CallHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService implements IMessageService {
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final RoomRepository roomRepository;
    private final MessageRepository messageRepository;
    private final RoomService roomService;
    private final S3UploadService s3UploadService;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final CallHandler callHandler;

    @Override
    @Transactional
    public void saveMessage(ChatRequest chatRequest, Message messageTmp) throws PermissionAccessDenied {
        Group group = checkPermissionInChatGroup(chatRequest);
        Message message = convertToMessage(chatRequest);
        message.setId(messageTmp.getId());
        message.setRoomId(messageTmp.getRoomId());
        String roomIdConvert = message.getRoomId();
        message.setRoomId(roomIdConvert);
        User user = userRepository.findByEmail(chatRequest.getSenderId()).orElseThrow();
        message.setSenderName(user.getName());
        message.setSenderAvatar(user.getAvatar());
        try {
            if (chatRequest.getFileContent() != null) {
                s3UploadService.uploadFile(chatRequest.getFileContent(), message);
            } else {
                LocalDateTime time = LocalDateTime.now();
                message.setMessageStatus(MessageStatus.SENT);
                message.setSendDate(time);
                messageRepository.save(message);
                List<Room> rooms = roomService.findByRoomId(message.getRoomId());

                for (Room room : rooms) {
                    if(group != null) {
                        List<String> members = group.getMembers();
                        if(!members.contains(room.getSenderId())) continue;
                    }
                    if (Objects.equals(room.getSenderId(), message.getSenderId())) {
                        if (message.getContent() instanceof FileObject) room.setLatestMessage(message.getMessageType().toString());
                        else room.setLatestMessage(message.getContent().toString());
                        room.setTime(time);
                        room.setSender(true);
                        room.setNumberOfUnreadMessage(0);
                        Room roomRs = roomService.saveRoom(room);
                        UserNotify success = UserNotify.builder()
                                .status("SUCCESS")
                                .senderId(message.getSenderId())
                                .receiverId(message.getReceiverId())
                                .message(message)
                                .room(roomRs)
                                .build();
                        simpMessagingTemplate.convertAndSendToUser(
                                message.getSenderId(), "queue/messages",
                                success
                        );
                    } else {
                        if (message.getContent() instanceof FileObject) {
                            if(isGroupChat(room.getRoomId())) {
                                room.setLatestMessage(user.getName() + ": " +message.getMessageType().toString());
                            } else {
                                room.setLatestMessage(message.getMessageType().toString());
                            }

                        } else {
                            if(isGroupChat(room.getRoomId())) {
                                room.setLatestMessage(user.getName() + ": " + message.getContent().toString());
                            } else
                                room.setLatestMessage(message.getContent().toString());
                        }
                        room.setNumberOfUnreadMessage(room.getNumberOfUnreadMessage() + 1);
                        room.setTime(time);
                        room.setSender(false);
                        roomService.saveRoom(room);
                    }
                }

                UserNotify sent = UserNotify.builder()
                        .status("SENT")
                        .senderId(message.getSenderId())
                        .receiverId(message.getReceiverId())
                        .message(message)
                        .build();

                simpMessagingTemplate.convertAndSendToUser(
                        message.getReceiverId(), "queue/messages",
                        sent
                );
            }


        } catch (Exception e) {
            List<Room> rooms = roomService.findByRoomId(roomIdConvert);
            for (Room room : rooms) {
                if (Objects.equals(room.getSenderId(), message.getSenderId())) {
                    if (message.getContent() instanceof FileObject fileObject) {
                        room.setLatestMessage(fileObject.getFilename());
                    } else room.setLatestMessage(message.getContent().toString());
                    room.setTime(LocalDateTime.now());
                    room.setSender(true);
                    room.setNumberOfUnreadMessage(0);
                    roomService.saveRoom(room);
                    break;
                }
            }

        }

    }
    public boolean isGroupChat(String roomId) {
        return groupRepository.findById(roomId).isPresent();
    }

    private String getRoomIdConvert(String senderId, String receiverId) throws DataNotFoundException {
        var roomId = roomService.getRoomId(senderId, receiverId);
        return roomId.orElseThrow(() -> new DataNotFoundException("room not found"));
    }


    @Override
    public MessageResponse getAllByRoomId(String senderId, String roomId, PageRequest pageRequest) {
        Optional<Group> group = groupRepository.findById(roomId);
        Page<Message> messagePage = messageRepository.getAllByRoomId(roomId, pageRequest);
        // kiểm tra trong trường hợp room này là group_chat
        if (group.isPresent()) {
            List<String> members = group.get().getMembers();
            // nếu group inactive thì chỉ trả về các tin nhắn hệ thống
            if (group.get().getGroupStatus().equals(GroupStatus.INACTIVE)) {
                List<Message> messagesSystem = messagePage.getContent()
                        .stream()
                        .filter(msg -> msg.getMessageType().equals(MessageType.SYSTEM))
                        .sorted(Comparator.comparing(Message::getSendDate))
                        .toList();
                return MessageResponse.builder()
                        .messages(messagesSystem)
                        .totalPage(0)
                        .build();
            }
            // nếu user không có trong group => không trả về message
            if(!members.contains(senderId)) {
                return MessageResponse.builder()
                        .messages(new ArrayList<>())
                        .totalPage(0)
                        .build();
            }
        }

        List<Message> messagesSend = messagePage.getContent().stream().filter(msg ->
                msg.getSenderId().equals(senderId)
        ).toList();
        List<Message> messagesReceive = messagePage.getContent().stream().filter(msg ->
                !msg.getSenderId().equals(senderId)
        ).toList();
        List<Message> messagesFilter = messagesReceive.stream().filter(msg ->
                !msg.getMessageStatus().equals(MessageStatus.SENDING) &&
                        !msg.getMessageStatus().equals(MessageStatus.ERROR)
        ).toList();
        List<Message> results = Stream
                .concat(messagesSend.stream(), messagesFilter.stream())
                .sorted(Comparator.comparing(Message::getSendDate))
//                .peek(msg -> {
//                    if (!msg.getSenderId().equals(senderId)) {
//                        User sender = userRepository.findByEmail(msg.getSenderId()).orElseThrow();
//                        msg.setSenderAvatar(sender.getAvatar());
//                        msg.setSenderName(sender.getName());
//                    }
//                })
                .toList();
        return MessageResponse.builder()
                .messages(results)
                .totalPage(messagePage.getTotalPages())
                .build();

    }



    @Override
    public void updateMessage(String id, ChatRequest chatRequest) {
        Message message = convertToMessage(chatRequest);
        Message newMsg = messageRepository.findById(id).orElseThrow();
        newMsg.setMessageStatus(message.getMessageStatus());
        newMsg.setMessageType(message.getMessageType());
        newMsg.setMessageStatus(message.getMessageStatus());
        newMsg.setContent(message.getContent());
        newMsg.setHiddenSenderSide(chatRequest.isHiddenSenderSide());
        messageRepository.save(newMsg);
    }

    @Override
    public Message saveMessage(ChatRequest chatRequest) throws DataNotFoundException, PermissionAccessDenied {
        String roomId = getRoomIdConvert(chatRequest.getSenderId(), chatRequest.getReceiverId());
        Room room = roomRepository.findBySenderIdAndReceiverId(chatRequest.getSenderId(),
                chatRequest.getReceiverId()).orElseThrow(() -> new DataNotFoundException("room not found"));
        if (room.getRoomType().equals(RoomType.GROUP_CHAT)) {
            checkPermissionInChatGroup(chatRequest);
        }
        Message message = convertToMessage(chatRequest);
        message.setSendDate(LocalDateTime.now());
        message.setRoomId(roomId);
        return messageRepository.save(message);
    }

    private Group checkPermissionInChatGroup(ChatRequest chatRequest) throws PermissionAccessDenied {
        // kiểm tra xem có phải cuộc trò chuyện nhóm hay không
        Optional<Group> optionalGroup = groupRepository.findById(chatRequest.getReceiverId());
        if (optionalGroup.isPresent()) {


            Group group = optionalGroup.get();
            // kiểm tra group có active hay không
            if(group.getGroupStatus().equals(GroupStatus.INACTIVE))
                throw new PermissionAccessDenied("group inactive");
            // kiểm tra user có trong group hay không
            List<String> members = group.getMembers();
            if (!members.contains(chatRequest.getSenderId()))
                throw new PermissionAccessDenied("user is not in group");

            // kiểm tra quyền của senderId trong nhóm nếu permission là only_admin hoặc only_owner
            SendMessagePermission sendMessagePermission = group.getSendMessagePermission();
            if (sendMessagePermission.equals(SendMessagePermission.ONLY_ADMIN)) {
                List<String> admins = group.getAdmins();
                if (!admins.contains(chatRequest.getSenderId()) &&
                        !group.getOwner().equals(chatRequest.getSenderId()))
                    throw new PermissionAccessDenied("only admins or owner can send message");
            }
            if (sendMessagePermission.equals(SendMessagePermission.ONLY_OWNER)) {
                if (!group.getOwner().equals(chatRequest.getSenderId()))
                    throw new PermissionAccessDenied("only owner can send message");
            }
            return group;
        }
        return null;
    }

    @Override
    public Message saveMessageForImageGroup(ChatImageGroupRequest chatImageGroupRequest) throws Exception {
        String roomId = getRoomIdConvert(chatImageGroupRequest.getSenderId(), chatImageGroupRequest.getReceiverId());
        Message message = convertImageGroupToMessage(chatImageGroupRequest);
        message.setRoomId(roomId);
        return messageRepository.save(message);
    }

    @Override
    public void revokeMessage(String messageId, String senderId, String receiverId) throws PermissionAccessDenied {
        Optional<Message> optionalMessage = messageRepository.findById(messageId);
        // kiểm tra user có đúng là người gửi hay không
        Message message = optionalMessage.orElseThrow();
        if (!message.getSenderId().equals(senderId))
            throw new PermissionAccessDenied("permission access denied");
        message.setMessageStatus(MessageStatus.REVOKED);
        messageRepository.save(message);
        UserNotify revoke = UserNotify.builder()
                .senderId(message.getSenderId())
                .receiverId(message.getReceiverId())
                .status("REVOKED_MESSAGE")
                .build();
        simpMessagingTemplate.convertAndSendToUser(
                receiverId, "queue/messages",
                revoke
        );
    }

    @Override
    @Transactional
    public void forwardMessage(String messageId, String senderId, List<String> receiversId) throws DataNotFoundException {
        Optional<Message> optionalMessage = messageRepository.findById(messageId);
        Message message = optionalMessage.orElseThrow();
        User sendUser = userRepository.findByEmail(senderId).orElseThrow(() -> new DataNotFoundException("user not found"));
        Message messageRs = null;
        for (String receiverId : receiversId) {
            Message newMsg;
            newMsg = message;
            newMsg.setId(null);
            newMsg.setSenderId(senderId);
            newMsg.setSenderAvatar(sendUser.getAvatar());
            newMsg.setSenderName(sendUser.getName());
            String roomId = getRoomIdConvert(senderId, receiverId);
            newMsg.setMessageStatus(MessageStatus.SENT);
            newMsg.setReceiverId(receiverId);
            newMsg.setSendDate(LocalDateTime.now());
            newMsg.setRoomId(roomId);
            messageRs = messageRepository.save(newMsg);

            // update rooms
            List<Room> rooms = roomRepository.findByRoomId(roomId);
            for (Room room : rooms) {

                if (!room.getSenderId().equals(senderId)) {
                    if (message.getContent() instanceof FileObject) {
                        room.setLatestMessage(message.getMessageType().toString());
                    } else room.setLatestMessage(message.getContent().toString());
                    room.setTime(LocalDateTime.now());
                    room.setSender(true);
                    room.setNumberOfUnreadMessage(room.getNumberOfUnreadMessage() + 1);
                    roomService.saveRoom(room);
                } else {
                    if (message.getContent() instanceof FileObject) {
                        room.setLatestMessage(message.getMessageType().toString());
                    } else room.setLatestMessage(message.getContent().toString());
                    room.setLatestMessage(message.getContent().toString());
                    room.setTime(LocalDateTime.now());
                    room.setSender(true);
                    room.setNumberOfUnreadMessage(0);
                    roomService.saveRoom(room);
                }
            }
            UserNotify success = UserNotify.builder()
                    .senderId(message.getSenderId())
                    .receiverId(message.getReceiverId())
                    .message(messageRs)
                    .status("SENT")
                    .build();
            simpMessagingTemplate.convertAndSendToUser(
                    message.getReceiverId(), "queue/messages",
                    success
            );

        }
        UserNotify success = UserNotify.builder()
                .senderId(message.getSenderId())
                .receiverId(message.getReceiverId())
                .status("SUCCESS")
                .message(messageRs)
                .build();
        simpMessagingTemplate.convertAndSendToUser(
                senderId, "queue/messages",
                success
        );
    }

    @Override
    public void saveImageGroupMessage(ChatImageGroupRequest chatImageGroupRequest, Message messageTmp)  {

    }

    @Override
    public void seenMessage(String roomId, String senderId, String receiverId) {
        List<Message> messagesSent = messageRepository.getAllByRoomIdAndMessageStatus(roomId, MessageStatus.SENT);
        messagesSent = messagesSent.stream().filter(msg -> !msg.getSenderId().equals(senderId)).toList();
        List<Message> messagesReceiver = messageRepository.getAllByRoomIdAndMessageStatus(roomId, MessageStatus.RECEIVED);
        messagesReceiver = messagesReceiver.stream().filter(msg -> !msg.getSenderId().equals(senderId)).toList();
        for (Message msgSent : messagesSent) {
            msgSent.setSeenDate(LocalDateTime.now());
            msgSent.setMessageStatus(MessageStatus.SEEN);
            messageRepository.save(msgSent);
        }
        for (Message msgReceive : messagesReceiver) {
            msgReceive.setSeenDate(LocalDateTime.now());
            msgReceive.setMessageStatus(MessageStatus.SEEN);
            messageRepository.save(msgReceive);
        }
        Optional<Room> optionalRoom = roomRepository.findBySenderIdAndReceiverId(senderId, receiverId);
        Room room = optionalRoom.orElseThrow();
        room.setNumberOfUnreadMessage(0);
        roomRepository.save(room);
        Message latestMessage = messageRepository.findTopByOrderBySendDateDesc();
        UserNotify seen = UserNotify.builder()
                .senderId(latestMessage.getSenderId())
                .receiverId(latestMessage.getReceiverId())
                .status("SEEN")
                .build();
        simpMessagingTemplate.convertAndSendToUser(
                receiverId, "queue/messages",
                seen
        );
    }

    @Override
    public Message saveCall(CallRequest callRequest) throws DataNotFoundException, PermissionAccessDenied {
        String roomId = getRoomIdConvert(callRequest.getSenderId(), callRequest.getReceiverId());
        Room room = roomRepository.findBySenderIdAndReceiverId(callRequest.getSenderId(),
                callRequest.getReceiverId()).orElseThrow(() -> new DataNotFoundException("room not found"));
        CallInfo callInfo = CallInfo.builder()
                .callStatus(CallStatus.START)
                .build();
        LocalDateTime time = LocalDateTime.now();
        Message message = Message.builder()
                .senderId(callRequest.getSenderId())
                .receiverId(callRequest.getReceiverId())
                .roomId(roomId)
                .messageStatus(MessageStatus.SENT)
                .content(callInfo)
                .messageType(callRequest.getMessageType())
                .sendDate(time)
                .hiddenSenderSide(false)
                .build();
        Message messageRs = messageRepository.save(message);
        List<Room> rooms = roomRepository.findByRoomId(roomId);
        if (room.getRoomType().equals(RoomType.GROUP_CHAT)) {
            ChatRequest chatRequest = ChatRequest.builder()
                    .senderId(callRequest.getSenderId())
                    .receiverId(callRequest.getReceiverId())
                    .build();
            checkPermissionInChatGroup(chatRequest);
            for (Room roomGroup : rooms) {
                if(roomGroup.getSenderId().equals(callRequest.getSenderId())) {
                    roomGroup.setLatestMessage("Đã bắt đầu cuộc gọi nhóm");
                    roomGroup.setSender(true);
                    roomGroup.setNumberOfUnreadMessage(0);
                    roomGroup.setTime(time);
                } else {
                    User user = userRepository.findByEmail(callRequest.getSenderId()).orElseThrow();
                    roomGroup.setLatestMessage(user.getName() + " đã bắt đầu cuộc gọi nhóm");
                    roomGroup.setSender(false);
                    roomGroup.setNumberOfUnreadMessage(roomGroup.getNumberOfUnreadMessage() + 1);
                    roomGroup.setTime(time);
                }
                roomRepository.save(roomGroup);
            }
        } else {
            for (Room roomUser : rooms) {
                String latestMessage;
                if(callRequest.getMessageType().equals(MessageType.AUDIO_CALL)) {
                    latestMessage = "Cuộc gọi thoại";
                } else {
                    latestMessage = "Cuộc gọi video";
                }
                if(roomUser.getSenderId().equals(callRequest.getSenderId())) {
                    roomUser.setLatestMessage(latestMessage);
                    roomUser.setSender(true);
                    roomUser.setNumberOfUnreadMessage(0);
                    roomUser.setTime(time);
                } else {
                    roomUser.setLatestMessage(latestMessage);
                    roomUser.setSender(false);
                    roomUser.setNumberOfUnreadMessage(roomUser.getNumberOfUnreadMessage() + 1);
                    roomUser.setTime(time);
                }
                roomRepository.save(roomUser);
            }
        }
        UserNotify callRequestNotify = UserNotify.builder()
                .senderId(messageRs.getSenderId())
                .receiverId(messageRs.getReceiverId())
                .status("CALL_REQUEST")
                .message(messageRs)
                .build();
        simpMessagingTemplate.convertAndSendToUser(
                callRequest.getReceiverId(), "queue/messages",
                callRequestNotify
        );
        callHandler.startCall(messageRs);
        return messageRs;
    }

    @Override
    public void acceptCall(String messageId) {
        Message message = messageRepository.findById(messageId).orElseThrow();
        callHandler.acceptCall(message);
    }

    @Override
    public void rejectCall(String messageId) {
        Message message = messageRepository.findById(messageId).orElseThrow();
        callHandler.rejectCall(message);
    }

    @Override
    public void endCall(String messageId) {
        Message message = messageRepository.findById(messageId).orElseThrow();
        callHandler.endCall(message);
    }


    public Message convertImageGroupToMessage(ChatImageGroupRequest chatImageGroupRequest) throws Exception {
        List<FileObject> fileObjects = new ArrayList<>();
        if (!chatImageGroupRequest.getFilesContent().isEmpty()) {
            List<MultipartFile> files = chatImageGroupRequest.getFilesContent();
            for (MultipartFile file : files) {
                String fileName = file.getOriginalFilename();
                assert fileName != null;
                String[] fileExtensions = fileName.split("\\.");
                String fileRegex = "(\\S+(\\.(?i)(jpg|png|gif|bmp))$)";
                Pattern pattern = Pattern.compile(fileRegex);
                Matcher matcher = pattern.matcher(fileName);
                if (!matcher.matches()) throw new Exception("all files must be image");
                FileObject fileObject = FileObject.builder()
                        .filename(fileExtensions[0])
                        .fileExtension(fileExtensions[fileExtensions.length - 1])
                        .build();
                fileObjects.add(fileObject);
            }
            return Message.builder()
                    .senderId(chatImageGroupRequest.getSenderId())
                    .receiverId(chatImageGroupRequest.getReceiverId())
                    .messageType(chatImageGroupRequest.getMessageType())
                    .messageStatus(MessageStatus.SENDING)
                    .content(fileObjects)
                    .build();
        }
        return null;
    }

    public Message convertToMessage(ChatRequest chatRequest) {
        if (chatRequest.getFileContent() != null) {
            MultipartFile multipartFile = chatRequest.getFileContent();
            String fileName = multipartFile.getOriginalFilename();
            System.out.println(multipartFile.getSize());
            assert fileName != null;
            String[] fileExtensions = fileName.split("\\.");
            FileObject fileObject = FileObject.builder()
                    .filename(fileExtensions[0])
                    .fileExtension(fileExtensions[fileExtensions.length - 1])
                    .build();
            return Message.builder()
                    .senderId(chatRequest.getSenderId())
                    .receiverId(chatRequest.getReceiverId())
                    .messageType(chatRequest.getMessageType())
                    .messageStatus(MessageStatus.SENDING)
                    .content(fileObject)
                    .build();
        }
        return Message.builder()
                .senderId(chatRequest.getSenderId())
                .receiverId(chatRequest.getReceiverId())
                .messageType(chatRequest.getMessageType())
                .messageStatus(MessageStatus.SENDING)
                .content(chatRequest.getTextContent())
                .build();
    }


}
