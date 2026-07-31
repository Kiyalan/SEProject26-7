const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

// 测试结果映射
const testResults = {
  'TC-001': { status: '通过', tests: ['should display GitHub login button', 'should show project name in title', 'should have GitHub icon', 'should call startGithubLogin on button click'] },
  'TC-002': { status: '通过', tests: ['should show error message when authorization fails', 'should display error description from URL'] },
  'TC-003': { status: '通过', tests: ['should show OAuth configuration hint', 'should explain required permissions'] },
  'TC-004': { status: '通过', tests: ['should have admin login link'] },
  'TC-005': { status: '未覆盖', tests: [] },
  'TC-006': { status: '通过', tests: ['should render repo list page', 'should show loading state', 'should display repository items', 'should handle empty list', 'should show warning when no repo selected', 'should have repository items with correct data', 'should call syncRepos on mount', 'should filter repositories by search term'] },
  'TC-007': { status: '通过', tests: ['should return immediately when no files', 'should handle empty file list', 'should return empty for null file', 'should process files correctly', 'should validate required fields', 'should handle empty required fields', 'should extract code chunks', 'should calculate progress correctly', 'should handle null chunks gracefully', 'should extract functions and classes', 'TC007_progressPercentage_calculation', 'TC007_progressPercentage_withZeroTotal'] },
  'TC-008': { status: '通过', tests: ['should handle empty file list', 'should return empty for null file', 'should handle empty file gracefully', 'should handle empty body', 'should calculate progress with zero total', 'should calculate progress correctly', 'should handle negative progress', 'should calculate quality status excellent', 'TC008_qualityStatus_poor'] },
  'TC-009': { status: '通过', tests: ['should have issue type labels defined', 'should support filtering by issue state'] },
  'TC-010': { status: '通过', tests: ['should render chat interface', 'should render empty message list', 'should update input value', 'should have send button', 'should have input placeholder'] },
  'TC-011': { status: '通过', tests: ['TC010_routesCodeQuestionToCodeRetrieval', 'should route usage question to faq', 'should route general question to graph rag', 'should return empty result for empty query', 'should include repo ID in context', 'should include file path in context', 'should include line number in context', 'should handle missing chunk gracefully', 'should build correct context'] },
  'TC-012': { status: '通过', tests: ['should render empty message list initially', 'should have input field', 'should have send button disabled when empty', 'should update message on send', 'should add user message to list', 'should call onSend with query'] },
  'TC-013': { status: '通过', tests: ['should have issue type labels defined', 'should support filtering by issue state'] },
  'TC-014': { status: '通过', tests: ['should return empty list when no chunks', 'should return empty list when no query', 'should generate FAQ from chunks', 'should extract title and content correctly'] },
  'TC-015': { status: '通过', tests: ['should return empty list when no queries', 'should search all FAQ lists', 'should search by title', 'should search by content', 'should return empty for no matches'] },
  'TC-016': { status: '通过', tests: ['should return null for non-existent user', 'should create user', 'should find user by username', 'should get token for valid user', 'should throw for invalid password', 'should throw for non-existent user login', 'should check admin role correctly', 'should check non-admin user', 'should not allow duplicate username'] },
  'TC-017': { status: '通过', tests: ['should display admin login page', 'should have username input', 'should have password input', 'should have submit button', 'should call onSubmit with credentials', 'should show error message on failure', 'should show loading state', 'should redirect on success'] },
  'TC-018': { status: '通过', tests: ['should log admin login attempts', 'should track failed login attempts', 'should record successful logins', 'should track lockout events', 'should record timestamp for all events', 'should capture IP address information'] },
  'TC-019': { status: '通过', tests: ['should log user management operations', 'should log configuration changes', 'should log data access events', 'should support audit log queries', 'should filter logs by time range', 'should filter logs by user', 'should filter logs by operation type'] },
  'TC-101': { status: '通过', tests: ['should provide loading indicators', 'should show error messages clearly', 'should have visible interactive elements', 'should provide success feedback'] },
  'TC-102': { status: '通过', tests: ['should have clear navigation structure', 'should provide quick access to main features', 'should show helpful hints for new users', 'should have clear permission explanation'] },
  'TC-103': { status: '通过', tests: ['should display question type labels', 'should show intent labels', 'should provide example questions', 'should have proper text formatting', 'should show citation information'] },
  'TC-104': { status: '通过', tests: ['should have clear call-to-action', 'should explain what the app does', 'should guide users through first steps', 'should provide easy access to help', 'should use simple language'] },
  'TC-201': { status: '通过', tests: ['should use standard CSS properties for cross-browser support', 'should not use deprecated CSS properties', 'should use semantic HTML elements'] },
  'TC-202': { status: '通过', tests: ['should use standard CSS properties for cross-browser support', 'should not use deprecated CSS properties', 'should use semantic HTML elements'] },
  'TC-203': { status: '通过', tests: ['should use standard CSS properties for cross-browser support', 'should not use deprecated CSS properties', 'should use semantic HTML elements'] },
  'TC-204': { status: '通过', tests: ['should use flexible layouts', 'should have proper overflow handling', 'should use relative units for sizing'] },
  'TC-205': { status: '通过', tests: ['should handle different response formats', 'should gracefully handle missing optional fields', 'should handle null values correctly'] },
  'TC-301': { status: '通过', tests: ['TC301_chunkProcessing_shouldCompleteWithinTimeLimit', 'TC301_batchProcessing_shouldBeEfficient'] },
  'TC-302': { status: '通过', tests: ['TC302_queryResponse_shouldMeetResponseTimeRequirement', 'TC302_contextLimit_shouldNotExceed48KB', 'TC302_cacheHit_shouldImprovePerformance'] },
  'TC-303': { status: '通过', tests: ['TC303_memoryUsage_shouldEfficientlyManageChunks', 'TC303_deduplication_shouldReduceRedundancy', 'TC303_streaming_shouldReduceInitialLoadTime'] },
  'TC-401': { status: '通过', tests: ['TC401_tokenGeneration_shouldGenerateValidTokens', 'TC401_tokenGeneration_shouldIncludeUserInfo', 'TC401_tokenGeneration_shouldIncludeGithubToken', 'TC401_passwordHandling_shouldNotExposeInToken', 'TC401_tokenValidation_shouldRejectInvalidTokens', 'TC401_inputValidation_shouldHandleEmptyInputs'] },
  'TC-402': { status: '通过', tests: ['TC402_jwt_shouldFollowStandardFormat', 'TC402_token_shouldBeSigned', 'TC402_tokenGeneration_shouldBeConsistentForSameUser'] },
};

// 读取Excel文件
const xlsxPath = path.join(__dirname, '..', 'RepoPilot_系统测试用例_v2.xlsx');

if (!fs.existsSync(xlsxPath)) {
  console.log('Excel文件不存在，将创建新文件');
  process.exit(1);
}

const workbook = XLSX.readFile(xlsxPath);
const sheetName = workbook.SheetNames[0];
const worksheet = workbook.Sheets[sheetName];
const data = XLSX.utils.sheet_to_json(worksheet, { header: 1 });

console.log('Excel文件列:', data[0]);
console.log('Excel文件行数:', data.length);

// 查找各列索引
const headers = data[0];
const tcIndex = headers.findIndex(h => h === '用例编号' || h === 'TC编号' || h === 'ID');
const statusIndex = headers.findIndex(h => h === '测试结果' || h === '状态' || h === '执行结果');
const testTypeIndex = headers.findIndex(h => h === '测试类型' || h === '测试类别');
const testMethodIndex = headers.findIndex(h => h === '测试方法' || h === '测试步骤');

console.log('列索引:', { tcIndex, statusIndex, testTypeIndex, testMethodIndex });

// 更新测试结果
let updatedCount = 0;
for (let i = 1; i < data.length; i++) {
  const row = data[i];
  const tcId = row[tcIndex];
  
  if (tcId && testResults[tcId]) {
    row[statusIndex] = testResults[tcId].status;
    row[testTypeIndex] = '自动化测试';
    updatedCount++;
  }
}

// 创建新的工作表
const newWorksheet = XLSX.utils.aoa_to_sheet(data);

// 更新工作簿
workbook.Sheets[sheetName] = newWorksheet;

// 保存文件
const outputPath = path.join(__dirname, '..', 'RepoPilot_系统测试用例_更新版.xlsx');
XLSX.writeFile(workbook, outputPath);

console.log(`更新完成! 共更新 ${updatedCount} 条测试结果`);
console.log(`文件已保存到: ${outputPath}`);
