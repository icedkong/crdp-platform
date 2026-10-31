import type { ApiResponse } from '@fu/api'
import axios from 'axios'

export class ApiError extends Error {
  code: number
  constructor(message: string, code: number) {
    super(message)
    this.code = code
  }
}

export const http = axios.create({
  baseURL: '/',
  timeout: 15000,
})

export function setAuthToken(token: string) {
  if (token) {
    http.defaults.headers.common.Authorization = `Bearer ${token}`
  } else {
    delete http.defaults.headers.common.Authorization
  }
}

export async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  try {
    const response = await http.request<ApiResponse<T>>({ url: path, method, data: body })
    const payload = response.data
    if (!payload || payload.code !== 0) {
      throw new ApiError(payload?.message || '请求失败', payload?.code ?? 50000)
    }
    return payload.data
  } catch (error) {
    if (error instanceof ApiError) {
      throw error
    }
    if (axios.isAxiosError(error) && error.response?.data?.message) {
      const data = error.response.data as ApiResponse<unknown>
      throw new ApiError(data.message, data.code)
    }
    throw new ApiError('无法连接后端', 50000)
  }
}
