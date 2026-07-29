import React, { useState } from 'react';
import { Layout, Menu, Avatar, Input, Dropdown, Space } from 'antd';
import {
  HomeOutlined,
  QuestionCircleOutlined,
  BugOutlined,
  BookOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
  SearchOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { useNavigate, useLocation } from 'react-router-dom';

const { Header, Sider, Content } = Layout;

interface MainLayoutProps {
  children: React.ReactNode;
  rightPanel?: React.ReactNode;
}

const MainLayout: React.FC<MainLayoutProps> = ({ children, rightPanel }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(false);

  // 左侧导航菜单，和你原有菜单保持一致
  const menuItems: MenuProps['items'] = [
    {
      key: '/repos',
      icon: <HomeOutlined />,
      label: '概览',
    },
    {
      key: '/qa',
      icon: <QuestionCircleOutlined />,
      label: '智能问答',
    },
    {
      key: '/issues',
      icon: <BugOutlined />,
      label: 'Issue分析',
    },
    {
      key: '/knowledge',
      icon: <BookOutlined />,
      label: '知识库',
    },
    {
      type: 'divider',
    },
    {
      key: '/settings',
      icon: <SettingOutlined />,
      label: '设置',
    },
  ];

  const userMenuItems: MenuProps['items'] = [
    {
      key: 'settings',
      icon: <SettingOutlined />,
      label: '个人设置',
    },
    {
      type: 'divider',
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      danger: true,
    },
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {/* 左侧深色导航栏 */}
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        width={220}
        style={{
          background: 'var(--sidebar-bg)',
          overflow: 'auto',
          height: '100vh',
          position: 'sticky',
          top: 0,
          left: 0,
        }}
      >
        <div style={{
          height: 64,
          display: 'flex',
          alignItems: 'center',
          justifyContent: collapsed ? 'center' : 'flex-start',
          paddingLeft: collapsed ? 0 : 24,
          color: '#fff',
          fontSize: collapsed ? 18 : 16,
          fontWeight: 600,
          borderBottom: '1px solid rgba(255,255,255,0.1)'
        }}>
          {collapsed ? 'SE' : 'SEProject'}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
          style={{ borderRight: 'none' }}
        />
      </Sider>

      <Layout>
        {/* 顶部通栏 */}
        <Header style={{
          background: '#fff',
          padding: '0 24px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          boxShadow: '0 1px 4px rgba(0,0,0,0.04)',
          position: 'sticky',
          top: 0,
          zIndex: 10,
        }}>
          <div style={{ flex: 1, maxWidth: 480 }}>
            <Input
              placeholder="搜索仓库、Issue 或跳转..."
              prefix={<SearchOutlined style={{ color: 'var(--text-tertiary)' }} />}
              size="large"
              style={{ borderRadius: 6 }}
            />
          </div>
          <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
            <Space style={{ cursor: 'pointer' }}>
              <Avatar size="default" icon={<UserOutlined />} />
              <span style={{ color: 'var(--text-secondary)' }}>管理员</span>
            </Space>
          </Dropdown>
        </Header>

        {/* 主内容区 + 右侧操作面板 */}
        <Layout style={{ padding: 24, gap: 24, flexDirection: 'row' }}>
          <Content style={{ flex: 1, minWidth: 0 }}>
            {children}
          </Content>
          {rightPanel && (
            <div style={{
              width: 300,
              flexShrink: 0,
            }}>
              {rightPanel}
            </div>
          )}
        </Layout>
      </Layout>
    </Layout>
  );
};

export default MainLayout;