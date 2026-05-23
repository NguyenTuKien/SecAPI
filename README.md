# ỨNG DỤNG KỸ THUẬT MÃ HÓA VÀ PHÂN PHỐI KHÓA TRONG HỆ THỐNG WEB (ZERO-TRUST ARCHITECTURE)

## 1. Kiến trúc hệ thống

Hệ thống được thiết kế theo kiến trúc Zero-Trust với các thành phần chính bao gồm:
- **Client side**: Giao diện người dùng để truy cập vào hệ thống.
- **API Gateway**: Chịu trách nhiệm phân phối yêu cầu từ client đến các dịch vụ backend và thực hiện xác thực, ủy quyền.
- **Server side**: Bao gồm các dịch vụ backend xử lý logic nghiệp vụ và quản lý dữ liệu.
- **PostgreSQL**: Cơ sở dữ liệu để lưu trữ thông tin người dùng, khóa và các dữ liệu liên quan.
- **Redis**: Hệ thống lưu trữ tạm thời để quản lý phiên làm việc và các khóa phân phối.
---

## 2. Luồng hoạt động
### 2.1. Đăng kí 
- User nhập `username`, `password`, `confirmPassword` và `pinCode` vào form đăng kí.
- Phía client kiểm tra tính hợp lệ của dữ liệu (ví dụ: mật khẩu phải có độ dài tối thiểu, `confirmPassword` phải khớp với `password`, `pinCode` phải là số và có độ dài nhất định).
- Nếu dữ liệu hợp lệ, client sinh ra cặp khóa bất đối xứng, gồm `masterPublicKey` và `masterPrivateKey`.
- `masterPrivateKey` chỉ tồn tại tạm thời ở phía client trong quá trình đăng ký.
- Client dùng `pinCode`, `pinSalt` và `kdfParam` để sinh ra `keyEncryptionKey`.
- Client dùng `keyEncryptionKey` để mã hóa `masterPrivateKey`, tạo ra `encryptedPrivateKey`.
- Client không lưu `masterPrivateKey` gốc vào local storage. Nếu cần lưu cục bộ, client chỉ lưu `encryptedPrivateKey`.
- Sau đó gửi các thông tin cần lưu trữ lên server gồm:
```json
{
    "username": "exampleUser",
    "password": "plainPassword",
    "masterKey":
    {
        "publicKey": "publicKeyString",
        "encryptedPrivateKey": "encryptedPrivateKeyString",
        "privateKeyIv": "privateKeyIvString",
        "pinSalt": "randomSalt",
        "kdfParam": // Cấu hình hàm sinh khóa
        {
            "iterations": 100000,
            "keyLength": 256,
            "digest": "sha256"
        }
    }
}
```
- Server nhận và lưu trữ thông tin người dùng, với user bao gồm `username` và `hashPassword`. Trong đó `hashPassword` được tạo bằng BCrypt từ password người dùng gửi lên. `masterKey` bao gồm `publicKey`, `encryptedPrivateKey`, `privateKeyIv`, `pinSalt`, `kdfParam` cùng `userID` vừa tạo.
> **Chú thích về hàm sinh khóa từ PIN:**
>> Do `pinCode` thường ngắn và dễ đoán, hệ thống không dùng trực tiếp `pinCode` để mã hóa `masterPrivateKey`. Thay vào đó, client kết hợp `pinCode` với một chuỗi ngẫu nhiên `pinSalt`, sau đó đưa qua một hàm sinh khóa.
>>
>> Hàm này sẽ xử lý nhiều vòng lặp theo cấu hình trong `kdfParam` để tạo ra một khóa mạnh hơn gọi là `keyEncryptionKey`. `keyEncryptionKey` mới được dùng để mã hóa `masterPrivateKey`.
> 
> Công thức tổng quát:
>> `pinCode + pinSalt + kdfParam -> keyEncryptionKey`
> Ví dụ: hai người dùng cùng đặt PIN là `123456`, nhưng vì mỗi người có `pinSalt` khác nhau nên `keyEncryptionKey` được sinh ra cũng khác nhau. Điều này giúp hạn chế việc dò PIN hàng loạt.
>> 
>> `kdfParam` là phần mô tả cách sinh khóa, ví dụ:
>> - `iterations`: số vòng lặp xử lý. Số càng lớn thì việc dò PIN càng tốn thời gian hơn.
>> - `keyLength`: độ dài khóa đầu ra.
>> - `digest`: hàm băm bên trong quá trình sinh khóa.
> 
> Server chỉ lưu `pinSalt`, `kdfParam` và `encryptedPrivateKey`. Server không lưu `pinCode`, không lưu `keyEncryptionKey` và không lưu `masterPrivateKey` gốc.
> 
> Trong JavaScript, client sử dụng **Web Crypto API** để sinh cặp khóa, sinh khóa mã hóa từ `pinCode`, mã hóa `masterPrivateKey` và giải mã dữ liệu khi cần. Server chỉ nhận và lưu các dữ liệu đã mã hóa như `encryptedPrivateKey` và `encryptedConversationKey`; `masterPrivateKey` gốc không rời khỏi môi trường client.
---
### 2.2. Đăng nhập

* User nhập `username`, `password` vào form đăng nhập.
* Phía client kiểm tra tính hợp lệ của dữ liệu, ví dụ: `username` và `password` không được để trống.
* Client tạo một cặp khóa bất đối xứng mới cho phiên đăng nhập hiện tại, gồm:
    * `sessionPrivateKey` chỉ được lưu ở phía client trong phiên hiện tại.
    * `sessionPublicKey` được gửi lên server cùng yêu cầu đăng nhập.

```json
{
  "username": "exampleUser",
  "password": "plainPassword",
  "sessionPublicKey": "sessionPublicKeyString"
}
```

* Server kiểm tra thông tin đăng nhập.
* Nếu hợp lệ, server tạo `accessToken` và `refreshToken` cho phiên đăng nhập này.

* Server lưu `sessionPublicKey` và hash của `refreshToken` vào Redis, liên kết với `userId` và `sessionKeyId` duy nhất của phiên đăng nhập này.
* Server mã hóa `refreshToken` bằng `sessionPublicKey` để tạo ra `encryptRefreshToken`.

Ví dụ bản ghi Redis:

```json
{
  "sessionKeyId": "sessionKeyId",
  "userId": "exampleUserID",
  "sessionPublicKey": "sessionPublicKeyString",
  "masterPublicKey": "masterPublicKeyString",
  "refreshTokenHash": "hashRefreshTokenString",
  "status": "ACTIVE"
}
```

Trong đó:

```text
refreshTokenHash: dùng để server kiểm tra refreshToken khi client yêu cầu cấp token mới.
encryptRefreshToken: dùng để client lưu refreshToken an toàn hơn.
```

* Server trả về cho client:

```json
{
  "accessToken": "accessTokenString",
  "encryptRefreshToken": "encryptedRefreshTokenString",
  "masterKey": {
    "id": "masterKeyId",
    "publicKey": "masterPublicKeyString",
    "encryptedPrivateKey": "encryptedPrivateKeyString",
    "privateKeyIv": "privateKeyIvString",
    "pinSalt": "randomSalt",
    "kdfParam": {
      "iterations": 100000,
      "keyLength": 256,
      "digest": "sha256"
    }
  },
  "sessionKeyId": "sessionKeyId"
}
```

* Client nhận `accessToken`, `encryptRefreshToken`, thông tin `masterKey` và `sessionKeyId`.
* Client lưu `accessToken`, `encryptRefreshToken` và `sessionKeyId` vào bộ nhớ phù hợp.
* Sau khi đăng nhập thành công, client cho phép user tùy chọn nhập `pinCode`.

---

#### Trường hợp user nhập đúng `pinCode`

* Client dùng `pinCode`, `pinSalt` và `kdfParam` để sinh ra `keyEncryptionKey`.
* Client dùng `keyEncryptionKey` và `privateKeyIv` để giải mã `encryptedPrivateKey`.
* Nếu giải mã thành công, client lấy được `masterPrivateKey`.

Luồng:

```text
pinCode + pinSalt + kdfParam
→ keyEncryptionKey

encryptedPrivateKey + privateKeyIv + keyEncryptionKey
→ masterPrivateKey
```

* `masterPrivateKey` được giữ tạm ở client.
* `masterPrivateKey` dùng để giải mã các `encryptedConversationKey` đã được mã hóa cho `masterPublicKey`.
* Nhờ đó, client có thể lấy lại các `conversationKey` cũ và đọc được lịch sử tin nhắn từ các session trước.

---

#### Trường hợp user không nhập `pinCode` hoặc nhập sai

* Client không giải mã được `encryptedPrivateKey`, do đó không lấy được `masterPrivateKey`.
* Client vẫn giữ `sessionPrivateKey` của phiên hiện tại.
* Client vẫn có thể sử dụng ứng dụng.
* Tuy nhiên, client chỉ đọc được những dữ liệu có `encryptedConversationKey` được mã hóa cho `sessionPublicKey` của session hiện tại.
* Client không thể đọc lịch sử của các session trước đó, vì không có `masterPrivateKey` để khôi phục các `conversationKey` cũ.

---

#### Khi refresh token

* Khi `accessToken` hết hạn, client dùng `sessionPrivateKey` để giải mã `encryptRefreshToken`, lấy ra `refreshToken` hiện tại.

```text
encryptRefreshToken + sessionPrivateKey
→ refreshToken
```

* Client gửi `refreshToken` kèm `sessionKeyId` lên server để yêu cầu cấp token mới.

```json
{
  "sessionKeyId": "sessionKeyId",
  "refreshToken": "refreshTokenString"
}
```

* Server kiểm tra:

  * `sessionKeyId` có tồn tại trong Redis không.
  * Session tương ứng có còn trạng thái `ACTIVE` không.
  * Hash của `refreshToken` client gửi lên có khớp với `refreshTokenHash` đang lưu trong Redis không.

* Nếu hợp lệ, server tạo:

```text
accessToken mới
refreshToken mới
```

* Server cập nhật `refreshTokenHash` mới trong Redis.
* Server mã hóa `refreshToken` mới bằng `sessionPublicKey` đang lưu trong Redis để tạo `encryptRefreshToken` mới.
* Server trả về cho client:

```json
{
  "accessToken": "newAccessTokenString",
  "encryptRefreshToken": "newEncryptedRefreshTokenString",
  "sessionKeyId": "sessionKeyId"
}
```

* Client nhận `accessToken` mới và `encryptRefreshToken` mới.
* Client tiếp tục sử dụng `sessionPrivateKey` hiện tại để giải mã các dữ liệu thuộc session hiện tại.

Lưu ý:

```text
Các encryptedConversationKey được mã hóa cho sessionPublicKey
→ giải mã bằng sessionPrivateKey.

Các encryptedConversationKey được mã hóa cho masterPublicKey
→ giải mã bằng masterPrivateKey.
```

---

#### Khi logout hoặc session hết hạn

* Client xóa `sessionPrivateKey` khỏi bộ nhớ/local runtime.
* Server xóa bản ghi `SessionKey` tương ứng trong Redis.
* Khi bản ghi này bị xóa, `sessionPublicKey` của phiên đăng nhập đó không còn hiệu lực.
* Nếu user đăng nhập lại mà không nhập `pinCode`, client sẽ tạo một `SessionKey` mới và không đọc được dữ liệu của session trước.

---
### 2.3. Tạo cuộc trò chuyện mới

* User đã đăng nhập, nhập `conversationName` và chọn danh sách `participants` để tạo cuộc trò chuyện mới.
* Client tạo một khóa đối xứng ngẫu nhiên gọi là `conversationKey`.

```text
conversationKey
→ dùng để mã hóa / giải mã nội dung message trong conversation
```

* `conversationKey` chỉ được sinh và sử dụng ở phía client. Server không được biết `conversationKey` gốc.

---

#### 2.3.1. Lấy public key của participants

* Client gọi server để lấy danh sách public key cần thiết của các participant.

Mỗi participant có thể có:

```text
masterPublicKey
→ dùng để backup / khôi phục lịch sử bằng pinCode

sessionPublicKey
→ dùng cho các session đang active hiện tại
```

Vì hệ thống cho phép:

```text
1 user có nhiều session
```

nên một user có thể có nhiều `sessionPublicKey`.

Ví dụ một user có nhiều session active:

```text
User A
├── masterPublicKey_A
├── sessionPublicKey_A_web
└── sessionPublicKey_A_mobile
```

---

#### 2.3.2. Mã hóa conversationKey

* Client dùng từng public key để mã hóa `conversationKey`.

Có 2 loại bản mã hóa:

```text
recipientType = MASTER
→ conversationKey được mã hóa bằng masterPublicKey
→ dùng để backup và khôi phục lịch sử

recipientType = SESSION
→ conversationKey được mã hóa bằng sessionPublicKey
→ dùng để session hiện tại đọc được conversation
```

Ví dụ User A có 1 `masterKey` và 2 session active:

```text
conversationKey + masterPublicKey_A
→ encryptedConversationKey cho MASTER của User A

conversationKey + sessionPublicKey_A_web
→ encryptedConversationKey cho SESSION web của User A

conversationKey + sessionPublicKey_A_mobile
→ encryptedConversationKey cho SESSION mobile của User A
```

---

#### 2.3.3. Payload gửi lên server

Client gửi request tạo conversation lên server. `accessToken` được gửi trong header.

Ví dụ payload:

```json
{
  "conversationName": "Example Conversation",
  "participants": ["userId1", "userId2"],
  "keyVersion": 1,
  "encryptedConversationKeys": [
    {
      "userId": "userId1",
      "recipientType": "MASTER",
      "recipientKeyId": "masterKeyId_user1",
      "encryptedConversationKey": "encryptedConversationKeyForMasterUser1"
    },
    {
      "userId": "userId1",
      "recipientType": "SESSION",
      "recipientKeyId": "sessionKeyId_user1_web",
      "encryptedConversationKey": "encryptedConversationKeyForSessionUser1Web"
    },
    {
      "userId": "userId1",
      "recipientType": "SESSION",
      "recipientKeyId": "sessionKeyId_user1_mobile",
      "encryptedConversationKey": "encryptedConversationKeyForSessionUser1Mobile"
    },
    {
      "userId": "userId2",
      "recipientType": "MASTER",
      "recipientKeyId": "masterKeyId_user2",
      "encryptedConversationKey": "encryptedConversationKeyForMasterUser2"
    },
    {
      "userId": "userId2",
      "recipientType": "SESSION",
      "recipientKeyId": "sessionKeyId_user2_web",
      "encryptedConversationKey": "encryptedConversationKeyForSessionUser2Web"
    }
  ]
}
```

Trong đó:

```text
conversationName:
- tên cuộc trò chuyện

participants:
- danh sách user tham gia conversation

keyVersion:
- phiên bản khóa của conversation
- message sẽ lưu keyVersion để biết cần dùng conversationKey phiên bản nào

recipientType:
- MASTER hoặc SESSION

recipientKeyId:
- nếu recipientType = MASTER thì đây là masterKeyId
- nếu recipientType = SESSION thì đây là sessionKeyId

encryptedConversationKey:
- conversationKey đã được mã hóa bằng public key tương ứng
```

---

#### 2.3.4. Server xử lý

Sau khi nhận request, server thực hiện:

```text
1. Xác thực accessToken.
2. Tạo bản ghi Conversations.
3. Tạo các bản ghi Participants.
4. Phân loại và lưu `encryptedConversationKey`:
  - Nếu `recipientType = MASTER` thì lưu vào Database.
  - Nếu `recipientType = SESSION` thì lưu tạm vào Redis theo `sessionKeyId`.
```

Bảng `Conversations`:

```text
Conversations
- ID
- name
- createdAt
- updatedAt
```

Bảng `Participants`:

```text
Participants
- ID
- ConversationID
- UserID
- role
- joinedAt
```

Bảng `ConversationKeyBackups` (Database):

```text
ConversationKeyBackups
- ID
- ConversationID
- UserID
- keyVersion
- masterKeyId
- encryptedConversationKey
- createdAt
```

SessionConversationKeys (Redis):

```text
session-conversation-key:{sessionKeyId}:{conversationId}:{keyVersion}
- sessionKeyId
- userId
- conversationId
- keyVersion
- encryptedConversationKey
- status
- expiresAt
```

Ví dụ dữ liệu trong `ConversationKeyBackups`:

```text
C1 | userId1 | v1 | masterKeyId_user1 | encryptedConversationKeyForMasterUser1
C1 | userId2 | v1 | masterKeyId_user2 | encryptedConversationKeyForMasterUser2
```

Server chỉ lưu `encryptedConversationKey`, không lưu `conversationKey` gốc.

---

#### 2.3.5. Sau khi tạo conversation thành công

* Client giữ `conversationKey` trong bộ nhớ tạm để mã hóa các message tiếp theo trong conversation.
* Khi user gửi message, client dùng `conversationKey` để mã hóa payload message.

Ví dụ payload message trước khi mã hóa:

```json
{
  "content": "Hello",
  "attachments": [],
  "clientCreatedAt": "now"
}
```

Client mã hóa:

```text
messagePayload + conversationKey + iv
→ cipherData
```

Server lưu message:

```text
Messages
- ID
- ConversationID
- SenderID
- cipherData
- iv
- keyVersion
- createdAt
- updatedAt
```

#### 2.3.6. Lưu trữ `ConversationKey`

Hệ thống **không lưu `conversationKey` gốc** ở server, Redis hoặc database. `conversationKey` gốc chỉ tồn tại ở phía client sau khi được tạo hoặc sau khi giải mã thành công.

Server chỉ lưu các bản `encryptedConversationKey`, tức là `conversationKey` đã được mã hóa bằng public key tương ứng.

Có 2 loại bản mã hóa:

```text
recipientType = MASTER
→ dùng để backup / khôi phục lịch sử
→ lưu bền vững trong Database
```

```text
recipientType = SESSION
→ dùng cho phiên đăng nhập hiện tại
→ lưu tạm trong Redis
```

Cụ thể:

```text
encryptedConversationKey cho `masterKey`
→ lưu trong DB
→ user nhập đúng pinCode thì dùng masterPrivateKey để giải mã
→ khôi phục được conversationKey và đọc lại lịch sử
```

```text
encryptedConversationKey cho SessionKey
→ lưu trong Redis
→ sessionPrivateKey của phiên hiện tại dùng để giải mã
→ khi logout hoặc session hết hạn thì xóa khỏi Redis
```

Như vậy:

```text
Có pinCode / `masterKey`
→ đọc được lịch sử cũ

Không nhập pinCode
→ chỉ đọc được dữ liệu của session hiện tại

Logout
→ mất SessionKey
→ login lại không nhập pinCode thì không đọc được dữ liệu session trước
```

Câu chốt:

> `ConversationKey` gốc chỉ tồn tại ở client. Bản mã hóa cho `masterKey` được lưu trong DB để phục vụ backup và restore lịch sử. Bản mã hóa cho `SessionKey` được lưu trong Redis vì chỉ có giá trị trong phiên đăng nhập hiện tại.
### 2.4. Đọc và gửi tin nhắn trong conversation
#### 2.4.1. Nguyên tắc chung

* Nội dung tin nhắn **không được gửi lên server dưới dạng plaintext**.
* Mỗi conversation sẽ có một `conversationKey` dùng để mã hóa nội dung tin nhắn.
* Server chỉ lưu:

```text
cipherData
iv
keyVersion
ConversationID
SenderID
createdAt
```

* Server **không lưu**:

```text
message plaintext
conversationKey gốc
masterPrivateKey
sessionPrivateKey
pinCode
```

---

## 2.4.2. Đọc tin nhắn trong conversation

* Khi user mở một conversation, client gửi request lấy danh sách message.

Ví dụ request lấy message:

```http
GET /conversations/{conversationId}/messages
```

* Server trả về danh sách message đã mã hóa:

```json
{
  "conversationId": "conversationId",
  "messages": [
    {
      "messageId": "messageId1",
      "senderId": "userId1",
      "cipherData": "encryptedMessagePayload",
      "iv": "randomIv",
      "keyVersion": 1,
      "createdAt": "serverCreatedAt"
    }
  ]
}
```

* Client kiểm tra xem mình có `conversationKey` tương ứng với `keyVersion` của message hay chưa.

---

### Trường hợp 1: Client có `sessionPrivateKey`

Nếu message dùng `keyVersion = 1`, client sẽ tìm bản `encryptedConversationKey` dành cho `sessionKeyId` hiện tại trong Redis.

```text
encryptedConversationKey + sessionPrivateKey
→ conversationKey
```

Sau khi lấy được `conversationKey`, client giải mã message:

```text
cipherData + iv + conversationKey
→ messagePayload
```

Ví dụ message sau khi giải mã:

```json
{
  "content": "Hello",
  "attachments": [],
  "clientCreatedAt": "now"
}
```

---

### Trường hợp 2: Client đã nhập đúng `pinCode`

Nếu user đã nhập đúng `pinCode`, client đã có `masterPrivateKey`.

Client có thể lấy bản `encryptedConversationKey` dành cho `masterKeyId` từ DB.

```text
encryptedConversationKey + masterPrivateKey
→ conversationKey
```

Sau đó client dùng `conversationKey` để giải mã message:

```text
cipherData + iv + conversationKey
→ messagePayload
```

Trường hợp này giúp user đọc được lịch sử tin nhắn của các session cũ.

---

### Trường hợp 3: Client không có key phù hợp

Nếu client không có:

```text
sessionPrivateKey phù hợp
masterPrivateKey phù hợp
```

thì client không giải mã được message.

Khi đó giao diện có thể hiển thị:

```text
Không thể giải mã tin nhắn này. Hãy nhập PIN để khôi phục lịch sử.
```

---

## 2.4.3. Gửi tin nhắn trong conversation

* User nhập nội dung tin nhắn.
* Client tạo message payload.

Ví dụ:

```json
{
  "content": "Hello",
  "attachments": [],
  "clientCreatedAt": "now"
}
```

* Client kiểm tra xem hiện tại có `conversationKey` hợp lệ cho conversation này không.

---

### Trường hợp 1: Client đã có `conversationKey`

Client dùng `conversationKey` để mã hóa message payload.

```text
messagePayload + conversationKey + iv
→ cipherData
```

Sau đó client gửi message đã mã hóa lên server.

Ví dụ payload gửi lên server:

```json
{
  "conversationId": "conversationId",
  "cipherData": "encryptedMessagePayload",
  "iv": "randomIv",
  "keyVersion": 1
}
```

Server lưu vào bảng `Messages`:

```text
Messages
- ID
- ConversationID
- SenderID
- cipherData
- iv
- keyVersion
- createdAt
- updatedAt
```

Server không đọc được nội dung thật của message.

---

### Trường hợp 2: Client chưa có `conversationKey`

Khi client chưa có `conversationKey`, client không tạo key mới ngay. Client thử khôi phục theo thứ tự:

1. Nếu session hiện tại có `encryptedConversationKey` trong Redis:
  - Client dùng `sessionPrivateKey` để giải mã.
  - Nếu thành công, lấy lại được `conversationKey`.

2. Nếu user đã nhập đúng `pinCode` và có `masterPrivateKey`:
  - Client lấy `encryptedConversationKey` bản `MASTER` từ DB.
  - Dùng `masterPrivateKey` để giải mã.
  - Nếu thành công, lấy lại được `conversationKey` cũ.

3. Nếu cả hai cách trên đều không thành công:
  - Client mới tạo `conversationKey` mới.
  - Tăng `keyVersion`.
  - Mã hóa key mới cho `masterPublicKey` và các `sessionPublicKey` active.
  - Message mới sẽ dùng `keyVersion` mới.

Ví dụ:

```text
Conversation C1
- keyVersion 1: dùng cho message cũ
- keyVersion 2: dùng cho message mới từ session hiện tại
```

Khi phải tạo key mới, client thực hiện:

```text
1. Tạo conversationKey mới.
2. Tăng keyVersion.
3. Lấy masterPublicKey của các participants.
4. Lấy sessionPublicKey của các session active từ Redis.
5. Mã hóa conversationKey mới cho `masterPublicKey` và `sessionPublicKey` tương ứng.
6. Gửi các encryptedConversationKey mới lên server. Server lưu các key này trước hoặc trong cùng thao tác với message mới để đảm bảo message luôn có key tương ứng.
7. Mã hóa message bằng conversationKey mới.
```

Ví dụ lưu key mới:

```json
{
  "conversationId": "conversationId",
  "keyVersion": 2,
  "encryptedConversationKeys": [
    {
      "userId": "userId1",
      "recipientType": "MASTER",
      "recipientKeyId": "masterKeyId_user1",
      "encryptedConversationKey": "encryptedConversationKeyForMasterUser1"
    },
    {
      "userId": "userId1",
      "recipientType": "SESSION",
      "recipientKeyId": "sessionKeyId_user1_web",
      "encryptedConversationKey": "encryptedConversationKeyForSessionUser1Web"
    }
  ]
}
```

Sau đó message mới sẽ được lưu với:

```text
keyVersion = 2
```

Như vậy:

```text
Message cũ dùng keyVersion 1
Message mới dùng keyVersion 2
```

Session mới không nhập PIN sẽ không đọc được message cũ, nhưng vẫn có thể đọc message mới phát sinh từ lúc session được cấp key mới.

---

## 2.4.4. Server xử lý khi nhận message

Khi server nhận request gửi message:

```json
{
  "conversationId": "conversationId",
  "cipherData": "encryptedMessagePayload",
  "iv": "randomIv",
  "keyVersion": 1
}
```

Server thực hiện:

```text
1. Xác thực accessToken.
2. Kiểm tra user có thuộc conversation không.
3. Lưu cipherData vào bảng Messages.
4. Gửi message realtime tới các session active của participants nếu có.
```

Server không giải mã `cipherData`.

---

## 2.4.5. Realtime message

Nếu participant đang online, server gửi message qua WebSocket.

Payload server gửi:

```json
{
  "messageId": "messageId",
  "conversationId": "conversationId",
  "senderId": "userId1",
  "cipherData": "encryptedMessagePayload",
  "iv": "randomIv",
  "keyVersion": 1,
  "createdAt": "serverCreatedAt"
}
```

Client nhận message, sau đó tự giải mã bằng `conversationKey` tương ứng với `keyVersion`.
