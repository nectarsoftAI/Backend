#!/bin/bash
TOKEN=$(printf 'protocol=https\nhost=github.com\n' | git credential fill 2>/dev/null | grep password | cut -d= -f2)

PR_BODY='## 주요 변경 사항\n\n### FileStorage DI 도입\n\n서비스 레이어(`AudioService`, `LiveService`, `LiveBufferProcessor`)가 `java.nio.file.Files`와 `Path`를 직접 호출해 로컬 파일 시스템에 강하게 결합돼 있었습니다.\n\n#### 추가: `storage/FileStorage` (인터페이스)\n\n| 메서드 | 역할 |\n|--------|------|\n| `saveUpload(filename, file)` | MultipartFile을 업로드 디렉토리에 저장 |\n| `saveTempCopy(source)` | 파일을 임시 디렉토리에 복사 |\n| `saveTempBytes(filename, data)` | byte[]를 임시 디렉토리에 저장 |\n| `delete(path)` | 파일 삭제 (없으면 무시) |\n\n#### 추가: `storage/LocalFileStorage` (구현체)\n\n- `@PostConstruct`에서 `uploads/`, `temp/` 디렉토리 생성 전담\n- `FileStorage` 구현체 교체만으로 저장 전략 변경 가능 (e.g. S3FileStorage)\n\n#### 리팩토링: `AudioService`\n\n- `FileStorage` 주입, 직접 `Files`/`Path` 사용 제거\n- `@PostConstruct` 디렉토리 생성 코드 → `LocalFileStorage`로 이동\n\n#### 리팩토링: `LiveService`\n\n- `MeetAiProperties` 의존성 제거\n- `private Path tempDir` 필드 및 `@PostConstruct init()` 제거\n- `processor.process()` 호출에서 `tempDir` 파라미터 제거\n\n#### 리팩토링: `LiveBufferProcessor`\n\n- `FileStorage` 주입, `Path tempDir` 파라미터 제거\n- `Files.write()` → `fileStorage.saveTempBytes()`\n- `Files.deleteIfExists()` → `fileStorage.delete()`\n\n---\n\n### run.bat .gitignore 추가\n\n`run.bat`에 OpenAI API 키가 하드코딩돼 있어 git 추적에서 제거하고 `.gitignore`에 추가했습니다.\n\n---\n\n## 효과\n\n- **단위 테스트**: `FileStorage` mock 주입으로 디스크 I/O 없이 테스트 가능\n- **저장 전략 교체**: 서비스 코드 수정 없이 구현체 교체만으로 가능\n- **단일 책임 원칙**: I/O 책임이 `LocalFileStorage` 한 곳으로 집중\n- **시그니처 단순화**: `LiveBufferProcessor.process()`에서 `Path tempDir` 파라미터 제거\n\nGenerated with Claude Code'

curl -s -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github.v3+json" \
  -H "Content-Type: application/json" \
  https://api.github.com/repos/nectarsoftAI/Backend/pulls \
  -d "{
    \"title\": \"Feat: 파일 저장소 추상화 — FileStorage DI 도입\",
    \"body\": \"$PR_BODY\",
    \"head\": \"mark\",
    \"base\": \"master\"
  }" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('html_url', d.get('message', str(d))))"
