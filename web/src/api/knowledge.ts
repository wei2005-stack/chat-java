import { client } from '@/api/generated_client'

export interface KnowledgeDocument {
  id: number
  uuid: string
  title: string
  chunkCount: number
  createdAt: string
}

export interface KnowledgeSearchHit {
  documentUuid: string
  documentTitle: string
  chunkIndex: number
  content: string
  score: number
}

interface KnowledgeDocumentListResponse {
  documents: KnowledgeDocument[]
}

interface KnowledgeSearchResponse {
  hits: KnowledgeSearchHit[]
}

const bearerAuth = [{ scheme: 'bearer', type: 'http' }] as const

export async function listKnowledgeDocuments(): Promise<KnowledgeDocument[]> {
  const response = await client.get<KnowledgeDocumentListResponse, unknown, true, 'data'>({
    url: '/api/knowledge/documents',
    responseStyle: 'data',
    throwOnError: true,
    security: bearerAuth,
  })
  return response.documents ?? []
}

export async function createKnowledgeDocument(title: string, content: string): Promise<KnowledgeDocument> {
  const response = await client.post<KnowledgeDocument, unknown, true, 'data'>({
    url: '/api/knowledge/documents',
    body: { title, content },
    headers: { 'Content-Type': 'application/json' },
    responseStyle: 'data',
    throwOnError: true,
    security: bearerAuth,
  })
  return response
}

export async function deleteKnowledgeDocument(uuid: string): Promise<void> {
  await client.delete<unknown, unknown, true, 'data'>({
    url: '/api/knowledge/documents/{uuid}',
    path: { uuid },
    responseStyle: 'data',
    throwOnError: true,
    security: bearerAuth,
  })
}

export async function searchKnowledge(q: string): Promise<KnowledgeSearchHit[]> {
  const response = await client.get<KnowledgeSearchResponse, unknown, true, 'data'>({
    url: '/api/knowledge/search',
    query: { q },
    responseStyle: 'data',
    throwOnError: true,
    security: bearerAuth,
  })
  return response.hits ?? []
}
