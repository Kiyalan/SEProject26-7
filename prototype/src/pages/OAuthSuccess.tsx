import { Spin, Typography } from 'antd'
import { useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { setAuth } from '../lib/auth'

export default function OAuthSuccess() {
  const [params] = useSearchParams()
  const navigate = useNavigate()

  useEffect(() => {
    const token = params.get('access_token')
    const username = params.get('username')

    if (token && username) {
      setAuth(token, username)
      navigate('/repos', { replace: true })
      return
    }

    navigate('/login?error=missing_token', { replace: true })
  }, [navigate, params])

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}>
      <Spin size="large" />
      <Typography.Text type="secondary" style={{ marginTop: 16 }}>
        正在完成 GitHub 授权...
      </Typography.Text>
    </div>
  )
}
